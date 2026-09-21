#!/bin/bash
# Stryker helper-VM role setup.
#
# Turns this VM into a lean offsec sidecar / obfuscation layer for vm0:
#   - headless server (the Stryker image already boots to a shell; this is belt-and-braces)
#   - tor (SOCKS relay for egress obfuscation)
#   - nginx (payload host on :80)
#   - dnsmasq (DNS)
#   - tcpdump + socat (traffic capture / relay glue)
#   - KEEPS the Realtek (RTL8812AU) driver and aircrack-ng so the external adapter
#     can still be put into monitor mode for handshake capture.
#
# Idempotent: safe to re-run. Never removes the WiFi driver or aircrack tooling.

set -u
export DEBIAN_FRONTEND=noninteractive

err() { echo "helper-setup <<"; echo "  FAILED: $*"; exit 1; }

echo "helper-setup <<"
echo "  refreshing package index"
apt-get update -y || err "apt-get update failed (check network)"

echo "  installing server tooling (tor, nginx, dnsmasq, tcpdump, socat)"
apt-get install -y --no-install-recommends \
    tor nginx dnsmasq tcpdump socat procps ca-certificates || err "package install failed"

# --- Headless: don't auto-start a display manager. The image is GUI-on-demand
#     (vncserver-start), so this is mostly a no-op safety net. ---
echo "  forcing headless boot"
if command -v systemctl >/dev/null 2>&1; then
    systemctl set-default multi-user.target 2>/dev/null || true
    systemctl disable lightdm gdm3 gdm sddm 2>/dev/null || true
    systemctl mask lightdm gdm3 gdm sddm 2>/dev/null || true

# Silence systemd-ssh-generator: it retries SSH-over-vsock every few seconds, which this
# VM does not provide, spamming the console with harmless "AF_VSOCK CID" errors.
systemctl mask systemd-ssh-generator.service systemd-ssh-generator.socket 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-ssh-generator 2>/dev/null || true
fi

# --- KEEP the Realtek driver intact. We do NOT remove it; we only verify it is still
#     loadable (the module ships in the base image) and that aircrack-ng is present. ---
echo "  verifying Realtek (RTL8812AU) driver + aircrack tooling"
MOD=""
for m in 88XXau 8812au rtl8812au; do
    if modprobe "$m" 2>/dev/null; then MOD="$m"; break; fi
done
if [ -n "$MOD" ]; then
    echo "  Realtek driver loaded: $MOD"
else
    echo "  WARN: RTL8812AU module not loadable (adapter not present?); driver files are untouched"
fi
if ! command -v aircrack-ng >/dev/null 2>&1 || ! command -v airodump-ng >/dev/null 2>&1; then
    echo "  installing aircrack-ng (handshake capture)"
    apt-get install -y --no-install-recommends aircrack-ng 2>/dev/null || echo "  WARN: aircrack-ng install skipped"
else
    echo "  aircrack-ng present"
fi

# --- Services ---
echo "  configuring tor (SOCKS on 9050)"
if command -v systemctl >/dev/null 2>&1; then
    systemctl enable tor 2>/dev/null || true
    systemctl restart tor 2>/dev/null || true
fi

echo "  configuring nginx payload host (:80)"
mkdir -p /var/www/payloads
echo "Stryker helper" > /var/www/html/index.html 2>/dev/null || true
if command -v systemctl >/dev/null 2>&1; then
    systemctl enable nginx 2>/dev/null || true
    systemctl restart nginx 2>/dev/null || true
fi

echo "  configuring dnsmasq (:53)"
if command -v systemctl >/dev/null 2>&1; then
    systemctl enable dnsmasq 2>/dev/null || true
    systemctl restart dnsmasq 2>/dev/null || true
fi

# Marker so the app can verify the helper role is applied.
mkdir -p /HELPER
echo "1" > /HELPER/.version

echo "  done"
echo "helper-setup >>"
