# Standalone open5gs MME for the EPC lab (epc profile).
# Builds ONLY the MME control-plane daemon from the in-tree open5gs source
# (loadtest/ims-tools/open5gs). The MME is a pure S6a CLIENT (freeDiameter
# ConnectPeer -> our epc-hss) + S11 CLIENT (gtpc.client.sgwc -> our epc-sgwc).
# It needs NO MongoDB: `ogs_dbi_init` is only called by udr/hss/pcrf/pcf, not
# open5gs-mmed — subscriber auth vectors come from our epc-hss over S6a.
#
#   docker compose ... --profile epc up -d --build mme
#
# Runtime config is mounted read-only from ./config/mme.yaml and ./config/mme.conf.
FROM ubuntu:24.04 AS open5gs-mme-builder

# Build + runtime deps in one image so the loader finds every linked lib
# (dev packages carry the runtime .so). Matches open5gs Debian Build-Depends.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential pkg-config meson ninja-build cmake git ca-certificates \
    flex bison python3 curl iproute2 \
    libgnutls28-dev libgcrypt-dev libssl-dev libidn-dev libidn11-dev \
    libmongoc-dev libbson-dev libsctp-dev libyaml-dev \
    libmicrohttpd-dev libcurl4-gnutls-dev libnghttp2-dev \
    libtins-dev libtalloc-dev libc-ares-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /open5gs
# Context is the monorepo root (see docker-compose.services.yml mme.build.context=.).
COPY loadtest/ims-tools/open5gs/ /open5gs/

# --prefix=/usr + --sysconfdir=/etc => binary /usr/bin/open5gs-mmed,
# configs expected at /etc/open5gs/, freeDiameter at /etc/freeDiameter/,
# tls certs at /etc/open5gs/tls/ (installed by configs/meson.build).
# Full install mirrors the official (meson build && ninja -C build install).
RUN meson setup build --prefix=/usr --sysconfdir=/etc && \
    ninja -C build install

# Control-plane only — S6a (freeDiameter) 3868, S11 (GTPv2-C) 2123/udp.
# S1-MME SCTP 36412 is listed for reference; the lab smoke path uses S6a+S11 only.
EXPOSE 3868 2123/udp 36412/sctp

CMD ["open5gs-mmed", "-c", "/etc/open5gs/mme.yaml"]