#!/usr/bin/env bash
# Lab self-signed cert for nginx :8443 (N3). Not for production CA.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$DIR"
CRT="$DIR/elisa-edge.crt"
KEY="$DIR/elisa-edge.key"
if [[ -f "$CRT" && -f "$KEY" ]]; then
  echo "certs already exist: $CRT"
  exit 0
fi
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$KEY" -out "$CRT" \
  -subj "/CN=pcscf.ims.lab/O=Elisa Lab/C=ZW" \
  -addext "subjectAltName=DNS:pcscf.ims.lab,DNS:elisa.ims.lab,DNS:localhost,IP:127.0.0.1"
chmod 640 "$KEY"
echo "wrote $CRT"
