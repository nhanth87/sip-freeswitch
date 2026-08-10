# 3GPP / ETSI specs cached for Elisa IMS lab

Local cache of Rel-16 (lab-relevant) IMS specs used by Elisa CSCF / Cx / charging design.
**Copyright:** ETSI / 3GPP — lab reference only; do not redistribute.

Upstream FTP (`www.3gpp.org/ftp/Specs/…`) returned **403** from this environment (Cloudflare).
PDFs were fetched from the public **ETSI deliver** mirror (`www.etsi.org/deliver/etsi_ts/…`).

| Spec | Title (short) | Cached ver | PDF | Markdown extract |
|------|---------------|------------|-----|------------------|
| **TS 23.228** | IMS architecture (Stage 2) | ETSI 123 228 V16.7.0 | [`pdf/TS_23.228.pdf`](pdf/TS_23.228.pdf) | [`md/TS_23.228.md`](md/TS_23.228.md) |
| **TS 24.229** | IMS SIP/SDP (Stage 3) | ETSI 124 229 V16.12.0 | [`pdf/TS_24.229.pdf`](pdf/TS_24.229.pdf) | [`md/TS_24.229.md`](md/TS_24.229.md) |
| **TS 29.228** | Cx/Dx signalling flows | ETSI 129 228 V16.1.0 | [`pdf/TS_29.228.pdf`](pdf/TS_29.228.pdf) | [`md/TS_29.228.md`](md/TS_29.228.md) |
| **TS 29.229** | Cx/Dx Diameter protocol | ETSI 129 229 V16.3.0 | [`pdf/TS_29.229.pdf`](pdf/TS_29.229.pdf) | [`md/TS_29.229.md`](md/TS_29.229.md) |
| **TS 32.260** | IMS charging | ETSI 132 260 V16.3.0 | [`pdf/TS_32.260.pdf`](pdf/TS_32.260.pdf) | [`md/TS_32.260.md`](md/TS_32.260.md) |
| **TS 32.299** | Diameter charging apps (Rf/Ro) | ETSI 132 299 V16.2.0 | [`pdf/TS_32.299.pdf`](pdf/TS_32.299.pdf) | [`md/TS_32.299.md`](md/TS_32.299.md) |
| **TS 33.203** | IMS access security (AKA) | ETSI 133 203 V16.0.0 | [`pdf/TS_33.203.pdf`](pdf/TS_33.203.pdf) | [`md/TS_33.203.md`](md/TS_33.203.md) |

## How extracts were produced

```bash
pdftotext -layout docs/3gpp/pdf/TS_XX.YYY.pdf /tmp/out.txt
# wrapped with metadata → docs/3gpp/md/TS_XX.YYY.md
```

Normative reference remains the **PDF**. Markdown is for grep / agent search.

## Related Elisa docs

- Protocol / Clearwater gap + phased plan: [`../agents/ims-protocol-roadmap.md`](../agents/ims-protocol-roadmap.md)
- Grill locks I1–I8 + charging Never: [`../agents/ims-core.md`](../agents/ims-core.md)
- Feature inventory: [`../agents/ims-feature-map.md`](../agents/ims-feature-map.md)
- SIP header preserve list: [`../agents/3gpp-sip-ims.md`](../agents/3gpp-sip-ims.md)

## Refresh

Re-download from ETSI when a newer Rel-16/17 patch is needed; keep version in the MD header table.
Do **not** commit secrets. Large PDFs (~14 MB) are intentional lab cache.
