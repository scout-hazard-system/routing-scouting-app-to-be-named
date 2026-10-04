#!/usr/bin/env bash
# Private CA + server certificate for the Scout edge (nginx on the Dell).
#
# One root CA, installed once on each client (Windows, NUC, Android), instead
# of a different self-signed cert per host. Run on the Dell (or any admin box)
# and keep ca.key OFFLINE after issuing — it can mint certs for anything.
#
#   ./make-private-ca.sh                       # CA (if missing) + server cert
#   SANS="DNS:scout.lan,IP:192.168.12.231" ./make-private-ca.sh
#
# Outputs (default ./pki):
#   ca.crt            install on clients (trust root)
#   ca.key            keep offline / chmod 600
#   scout-edge.crt    nginx ssl_certificate   (server + CA chain)
#   scout-edge.key    nginx ssl_certificate_key
#
# Free publicly-trusted alternative inside a tailnet: `tailscale cert <host>.ts.net`
# then point ssl_certificate / ssl_certificate_key at those files instead.
set -euo pipefail

OUT="${PKI_DIR:-./pki}"
CA_DAYS="${CA_DAYS:-3650}"
CERT_DAYS="${CERT_DAYS:-397}"   # <= 398 days so browsers accept it
CN="${CERT_CN:-scout.lan}"
SANS="${SANS:-DNS:scout.lan,DNS:scout,DNS:localhost,IP:192.168.12.231,IP:192.168.12.160,IP:10.66.0.1,IP:127.0.0.1}"

umask 077
mkdir -p "$OUT"

if [[ ! -f "$OUT/ca.key" ]]; then
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/ca.key"
  openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days "$CA_DAYS" \
    -subj "/CN=Scout Private Root CA/O=Scout" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$OUT/ca.crt"
  echo "[ca] created $OUT/ca.crt"
fi

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/scout-edge.key"
openssl req -new -key "$OUT/scout-edge.key" -subj "/CN=$CN/O=Scout" -out "$OUT/scout-edge.csr"
cat > "$OUT/scout-edge.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=$SANS
EOF
openssl x509 -req -in "$OUT/scout-edge.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
  -CAcreateserial -days "$CERT_DAYS" -sha256 -extfile "$OUT/scout-edge.ext" \
  -out "$OUT/scout-edge.leaf.crt"
cat "$OUT/scout-edge.leaf.crt" "$OUT/ca.crt" > "$OUT/scout-edge.crt"
rm -f "$OUT/scout-edge.csr" "$OUT/scout-edge.ext"
chmod 644 "$OUT/ca.crt" "$OUT/scout-edge.crt"

echo "[cert] $OUT/scout-edge.crt  SANs: $SANS"
echo "Install $OUT/ca.crt as a trusted root on each client; keep $OUT/ca.key offline."
