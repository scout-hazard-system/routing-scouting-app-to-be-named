#!/usr/bin/env bash
# Install the Scout nginx edge on the Dell (Debian Trixie). Idempotent.
#   sudo ./install-edge.sh
# Prints the generated IMAGORO_SERVE_TOKEN / BACKEND_PULL_API_KEY once so the
# upstream services can be restarted with matching values.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }

command -v nginx >/dev/null || { apt-get update -qq && apt-get install -y -qq nginx openssl; }

PKI=/etc/nginx/scout-pki
if [[ ! -f "$PKI/scout-edge.crt" ]]; then
  PKI_DIR="$PKI" bash "$HERE/make-private-ca.sh"
  # CA key must not live on the serving host long-term.
  echo "!! move $PKI/ca.key off this machine after issuing (keep it offline)"
fi
chmod 600 "$PKI"/*.key; chown root:root "$PKI"/*.key

SECRETS=/etc/nginx/scout-secrets.conf
if [[ ! -f "$SECRETS" ]]; then
  SERVE_TOKEN="$(openssl rand -hex 32)"
  PULL_KEY="$(openssl rand -hex 32)"
  umask 027
  cat > "$SECRETS" <<EOF
map "" \$imagoro_serve_token { default "$SERVE_TOKEN"; }
map "" \$scout_pull_key      { default "$PULL_KEY"; }
EOF
  chown root:www-data "$SECRETS"; chmod 640 "$SECRETS"
  echo "IMAGORO_SERVE_TOKEN=$SERVE_TOKEN"
  echo "BACKEND_PULL_API_KEY=$PULL_KEY"
  echo "(set these in the imagoro serve + scout backend environments, then restart them)"
fi

install -m 644 "$HERE/scout-edge.conf" /etc/nginx/conf.d/scout-edge.conf
rm -f /etc/nginx/sites-enabled/default
# :80 redirect only when nothing else (PXE http server) owns port 80.
if ! ss -ltnH '( sport = :80 )' | grep -q .; then
  install -m 644 "$HERE/scout-edge-http-redirect.conf" /etc/nginx/conf.d/scout-edge-http-redirect.conf
fi
nginx -t
systemctl enable --now nginx
systemctl reload nginx
echo "edge up: https://<dell>:443 (imagoro)  https://<dell>:8443 (scout backend)"
