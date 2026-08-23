#!/usr/bin/env bash
# Seed one lab subscriber + wrapped Ki/OPc/AMF + default APN profile row into
# postgres-epc / epc DB so the open5gs MME wire smoke (S6a AIR/AIA + ULR/ULA)
# has a real identity to fetch.
#
# Mirrors epc-hss at-rest rules exactly:
#   - epc_subscriber carries key_ref only (never cleartext Ki/OPc)
#   - epc_secret carries AES/ECB/NoPadding(KEK, 16-byte block) hex — same as
#     et.epc.hss.JdbcSecretStore + et.epc.crypto.AesEcb
#   - epc_apn_profile carries the relational APN configuration profile
#     (TS 23.008 §2.3; Flyway V6) — identity/QoS only, never secrets
#   - KEK default = epc-host default epc.hss.secret.kek-hex (EPC_KEK_HEX unset)
#
# Default credentials are the 3GPP TS 35.208 test-set vectors (lab only):
#   Ki=465B5CE8B199B49FAA5F0A2EE238A6BC  OPc=CD63CB71954A9F4E48A5994E37A02BAF
#   AMF=8000 (padded to 16 bytes; Milenage uses the first two)
#
# Usage:
#   ./epc/loadtest/scripts/epc-seed-subscriber.sh            # defaults below
#   IMSI=... MSISDN=... APN=... KI=... OPC=... KEK_HEX=... ./...epc-seed-subscriber.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

IMSI="${IMSI:-001010000000001}"
MSISDN="${MSISDN:-251900000001}"
MCC="${MCC:-001}"
MNC="${MNC:-01}"
APN="${APN:-internet}"
QCI="${QCI:-9}"
UE_AMBR_UL="${UE_AMBR_UL:-1000000000}"
UE_AMBR_DL="${UE_AMBR_DL:-1000000000}"
# APN configuration profile row (epc_apn_profile) AMBR — defaults to the UE AMBR
# so the seeded lab profile is uniformly gigabit.
APN_AMBR_UL="${APN_AMBR_UL:-$UE_AMBR_UL}"
APN_AMBR_DL="${APN_AMBR_DL:-$UE_AMBR_DL}"
KI="${KI:-465B5CE8B199B49FAA5F0A2EE238A6BC}"
OPC="${OPC:-CD63CB71954A9F4E48A5994E37A02BAF}"
AMF16="${AMF16:-80000000000000000000000000000000}"
KEK_HEX="${KEK_HEX:-000102030405060708090a0b0c0d0e0f}"

fail() { echo "SEED-FAIL: $*" >&2; exit 1; }

# 1) locate postgres-epc (compose first, then bare container name)
PG="$(docker compose -f docker-compose.yml -f docker-compose.services.yml \
      --profile epc ps -q postgres-epc 2>/dev/null || true)"
[ -n "$PG" ] || PG="$(docker ps --filter name=postgres-epc --format '{{.ID}}' | head -1)"
[ -n "$PG" ] || fail "postgres-epc container not running (compose --profile epc up first)"

# 2) wait healthy (pg_isready as the epc user)
for _ in $(seq 1 60); do
  if docker exec "$PG" pg_isready -U epc -d epc >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$PG" pg_isready -U epc -d epc >/dev/null 2>&1 || fail "postgres-epc not ready"

# 3) wait for Flyway HSS tables (V1 epc_subscriber / V2 epc_secret)
for _ in $(seq 1 60); do
  if docker exec "$PG" psql -U epc -d epc -tAc \
      "SELECT 1 FROM information_schema.tables WHERE table_name='epc_secret'" \
      2>/dev/null | grep -q 1; then break; fi
  sleep 1
done
docker exec "$PG" psql -U epc -d epc -tAc \
  "SELECT 1 FROM information_schema.tables WHERE table_name='epc_secret'" \
  | grep -q 1 || fail "epc_secret missing — epc-hss Flyway (db/migration-hss) did not run"

# 3b) wait for Flyway V6 APN configuration profile table (epc-host db/migration)
for _ in $(seq 1 60); do
  if docker exec "$PG" psql -U epc -d epc -tAc \
      "SELECT 1 FROM information_schema.tables WHERE table_name='epc_apn_profile'" \
      2>/dev/null | grep -q 1; then break; fi
  sleep 1
done
docker exec "$PG" psql -U epc -d epc -tAc \
  "SELECT 1 FROM information_schema.tables WHERE table_name='epc_apn_profile'" \
  | grep -q 1 || fail "epc_apn_profile missing — Flyway V6__epc_apn_profile did not run"

# 4) AES/ECB/NoPadding wrap Ki/OPc/AMF with the KEK (same primitive as JdbcSecretStore)
WRAP_TMP="$(mktemp -d)"
trap 'rm -rf "$WRAP_TMP"' EXIT
cat > "$WRAP_TMP/Wrap.java" <<'EOF'
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Wrap {
    public static void main(String[] args) throws Exception {
        byte[] kek = HexFormat.of().parseHex(args[0]);
        for (int i = 1; i < args.length; i++) {
            byte[] block = HexFormat.of().parseHex(args[i]);
            if (block.length != 16) throw new IllegalArgumentException("block " + i + " != 16B");
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"));
            System.out.println(HexFormat.of().formatHex(c.doFinal(block)));
        }
    }
}
EOF
WRAPPED="$(java "$WRAP_TMP/Wrap.java" "$KEK_HEX" "$KI" "$OPC" "$AMF16")" \
  || fail "AES wrap failed (need JDK 25 on host)"
KI_ENC="$(echo "$WRAPPED" | sed -n 1p)"
OPC_ENC="$(echo "$WRAPPED" | sed -n 2p)"
AMF_ENC="$(echo "$WRAPPED" | sed -n 3p)"

# 5) upsert subscriber + secret + default APN profile row (idempotent re-runs)
docker exec -i "$PG" psql -U epc -d epc >/dev/null <<SQL
INSERT INTO epc_subscriber
  (imsi, msisdn, enabled, mcc, mnc, default_apn, apns_csv, qos_qci,
   sqn, ue_ambr_ul_bps, ue_ambr_dl_bps, key_ref, updated_at)
VALUES
  ('$IMSI', '$MSISDN', TRUE, '$MCC', '$MNC', '$APN', '$APN', $QCI,
   0, $UE_AMBR_UL, $UE_AMBR_DL, '$IMSI', now())
ON CONFLICT (imsi) DO UPDATE SET
  msisdn = EXCLUDED.msisdn, enabled = TRUE,
  default_apn = EXCLUDED.default_apn, apns_csv = EXCLUDED.apns_csv,
  qos_qci = EXCLUDED.qos_qci, ue_ambr_ul_bps = EXCLUDED.ue_ambr_ul_bps,
  ue_ambr_dl_bps = EXCLUDED.ue_ambr_dl_bps, key_ref = EXCLUDED.key_ref,
  updated_at = now();
INSERT INTO epc_secret (imsi, ki_enc, opc_enc, amf_enc, kek_version, updated_at)
VALUES ('$IMSI', '$KI_ENC', '$OPC_ENC', '$AMF_ENC', 1, now())
ON CONFLICT (imsi) DO UPDATE SET
  ki_enc = EXCLUDED.ki_enc, opc_enc = EXCLUDED.opc_enc,
  amf_enc = EXCLUDED.amf_enc, updated_at = now();
-- Default APN configuration profile (TS 23.008 §2.3). DO NOTHING keeps manual
-- admin changes on re-runs; first seed wins the row with the lab QoS below.
INSERT INTO epc_apn_profile
  (imsi, apn, pdn_type, qci, arp_priority, preemption_capability,
   preemption_vulnerability, apn_ambr_ul_bps, apn_ambr_dl_bps,
   charging_characteristics, default_apn, updated_at)
VALUES
  ('$IMSI', '$APN', 'IPV4V6', $QCI, 8, FALSE, TRUE, $APN_AMBR_UL, $APN_AMBR_DL,
   0, TRUE, now())
ON CONFLICT (imsi, apn) DO NOTHING;
SQL

# 6) assert the rows are there (no secret values echoed back)
COUNT="$(docker exec "$PG" psql -U epc -d epc -tAc \
  "SELECT count(*) FROM epc_subscriber s JOIN epc_secret k USING (imsi) WHERE s.imsi='$IMSI'")"
[ "$COUNT" = "1" ] || fail "seed not visible in postgres-epc (count=$COUNT)"
APN_COUNT="$(docker exec "$PG" psql -U epc -d epc -tAc \
  "SELECT count(*) FROM epc_apn_profile WHERE imsi='$IMSI' AND apn='$APN'")"
[ "$APN_COUNT" = "1" ] || fail "apn profile row missing in postgres-epc (count=$APN_COUNT)"

echo "SEED-OK imsi=$IMSI msisdn=$MSISDN apn=$APN mcc=$MCC mnc=$MNC key_ref=$IMSI (Ki/OPc AES-wrapped, no cleartext in SQL; epc_apn_profile default row seeded)"
