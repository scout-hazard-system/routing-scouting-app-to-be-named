#!/usr/bin/env bash
# Install/refresh the Scout PXE server on the Dell. Idempotent. Run as root:
#   sudo NUC_PASSWORD_HASH='$6$...' ./install-pxe.sh
# NUC_PASSWORD_HASH: `openssl passwd -6` output for the agent box console
# password. AUTHORIZED_KEYS: file with the public keys to install on the box.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="${PXE_SERVER_IP:-192.168.1.100}"
NETBOOT_SRC="${NETBOOT_SRC:-/home/scout/netboot}"
AUTHORIZED_KEYS="${AUTHORIZED_KEYS:-$HERE/authorized_keys}"
: "${NUC_PASSWORD_HASH:?set NUC_PASSWORD_HASH (openssl passwd -6)}"
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
[[ -s "$AUTHORIZED_KEYS" ]] || { echo "missing $AUTHORIZED_KEYS"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
dpkg -s ipxe nginx dnsmasq >/dev/null 2>&1 || { apt-get update -qq; apt-get install -y -qq ipxe nginx dnsmasq; }
rm -f /etc/nginx/sites-enabled/default

# TFTP: iPXE binaries from the Debian ipxe package
install -d -m 755 /srv/pxe
install -m 644 /usr/lib/ipxe/undionly.kpxe /usr/lib/ipxe/ipxe.efi /srv/pxe/
# tftp-secure: dnsmasq only serves files owned by the user it runs as.
DNSMASQ_USER="$(ps -o user= -C dnsmasq | head -1)"; DNSMASQ_USER="${DNSMASQ_USER:-dnsmasq}"
chown "$DNSMASQ_USER" /srv/pxe/undionly.kpxe /srv/pxe/ipxe.efi

# HTTP: root-owned netboot tree, installer kernel/initrd + menu + preseed + keys
install -d -m 755 /srv/netboot /srv/netboot/trixie /srv/netboot/keys
install -m 644 "$NETBOOT_SRC/trixie/linux" "$NETBOOT_SRC/trixie/initrd.gz" /srv/netboot/trixie/
sed "s/^set server .*/set server $SERVER/" "$HERE/boot.ipxe" > /srv/netboot/boot.ipxe
sed -e "s|@@PASSWORD_HASH@@|$NUC_PASSWORD_HASH|" -e "s|@@SERVER@@|$SERVER|g" "$HERE/preseed.cfg.in" > /srv/netboot/preseed.cfg
install -m 644 "$AUTHORIZED_KEYS" /srv/netboot/keys/authorized_keys
install -m 644 "$HERE/00-scout-hardening.conf" /srv/netboot/keys/00-scout-hardening.conf
chmod 644 /srv/netboot/boot.ipxe /srv/netboot/preseed.cfg
grep -q '@@' /srv/netboot/preseed.cfg && { echo "unrendered placeholder in preseed"; exit 1; }

# Retire the old unauthenticated python http.server on 0.0.0.0:80
if systemctl list-unit-files scout-netboot.service >/dev/null 2>&1; then
  systemctl disable --now scout-netboot.service || true
fi

install -m 644 "$HERE/nginx-scout-netboot.conf" /etc/nginx/conf.d/scout-netboot.conf
install -m 644 "$HERE/dnsmasq-scout-pxe.conf" /etc/dnsmasq.d/scout-pxe.conf
# Old config pointed at the wrong subnet; keep a disabled copy for reference.
for f in /etc/dnsmasq.d/*; do
  [[ "$f" == /etc/dnsmasq.d/scout-pxe.conf || "$f" == *.disabled || "$f" == */README ]] && continue
  grep -q "dhcp-range=192.168.12.0,proxy" "$f" 2>/dev/null && mv "$f" "$f.disabled"
done
if grep -q "^dhcp-range=192.168.12.0,proxy" /etc/dnsmasq.conf 2>/dev/null; then
  cp -n /etc/dnsmasq.conf /etc/dnsmasq.conf.pre-scout
  sed -i '/^\(port=0\|domain-needed\|bogus-priv\|interface=eno1\|bind-interfaces\|dhcp-range=192.168.12.0,proxy.*\|dhcp-boot=ipxe.efi\|enable-tftp\|tftp-root=\/srv\/pxe\|pxe-service=.*\|log-dhcp\)$/d' /etc/dnsmasq.conf
fi

dnsmasq --test
nginx -t
systemctl enable --now nginx dnsmasq
systemctl restart dnsmasq
systemctl reload nginx
echo "PXE ready: TFTP /srv/pxe, menu http://$SERVER/boot.ipxe (default = local boot)"
