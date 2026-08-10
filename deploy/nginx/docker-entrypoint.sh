#!/bin/sh
# Generate lab TLS cert if missing, validate config, exec nginx.
set -eu
CRT=/etc/nginx/certs/elisa-edge.crt
KEY=/etc/nginx/certs/elisa-edge.key
if [ ! -f "$CRT" ] || [ ! -f "$KEY" ]; then
  echo "[nginx-edge] generating lab self-signed cert for pcscf.ims.lab"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "$KEY" -out "$CRT" \
    -subj "/CN=pcscf.ims.lab/O=Elisa Lab/C=ZW"
fi
nginx -t
exec /docker-entrypoint.sh "$@"
