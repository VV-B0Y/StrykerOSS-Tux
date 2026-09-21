# Virtual AP (Evil Twin / Rogue AP) — Design

Status: spec, awaiting sign-off on open decisions.

## Goal

The helper VM broadcasts a virtual access point off the RTL8812AU dongle, so nearby clients
connect to it, and we capture WPA handshakes and serve a captive-portal phishing page. This is
the evil-twin / rogue-AP attack — the most reliable handshake-capture method because we force
the client onto our AP instead of waiting for a passive handshake.

## Where it runs

Everything runs inside the helper VM (vm1), which already has most of the pieces from its role
provisioning. The RTL8812AU dongle is passed into the helper via USB (exclusive — vm0 can't use
it at the same time; existing passthrough machinery).

| Component | Status | Role |
|---|---|---|
| hostapd | **new** | AP radio — `driver=nl80211`, AP mode on the 88XXau driver |
| dnsmasq | present | DHCP for AP clients |
| nginx | present | captive-portal / phishing page |
| aircrack-ng + aireplay-ng | present | deauth + handshake capture |
| RTL8812AU dongle | present | the AP radio (passed through) |
| tor | present | optional internet egress for the lure (Phase 3) |

## Workflow (evil twin)

1. airodump-ng scans; operator picks a target SSID/BSSID/channel.
2. hostapd brings the dongle up in AP mode with the target SSID (or a lure SSID).
3. dnsmasq serves DHCP on the AP interface.
4. nginx serves the captive portal; iptables redirects all HTTP to it.
5. aireplay-ng deauths the real AP's clients → they reconnect to ours → handshake captured
   (aircrack-ng) and the portal is served.
6. Captured handshakes land in the shared `captured/` dir (already wired into the app).

## App changes

- A Virtual AP flow in the dashboard: pick target SSID + channel, start/stop the AP, see a
  handshake counter + captive-portal hits.
- Dongle → helper passthrough on demand (reuse existing USB manager).
- Provisioning: install hostapd into the helper (extend `helper_server.sh`).
- Config generation: `hostapd.conf` + `dnsmasq.conf` + iptables rules, driven by the app, shipped
  into the guest over the exec port (same base64 path the helper role uses).

## Phases

1. hostapd provisioning + AP mode on the dongle — prove the 88XXau driver hosts an AP in the helper.
2. dnsmasq DHCP + nginx captive portal + iptables redirect — full rogue AP.
3. Evil-twin automation — scan → clone SSID → deauth → capture, wired to the app.
4. App UI — start/stop, target picker, handshake + portal-hit counters.

## Decisions (locked)

1. AP modes: **both** evil twin (clone a target SSID) and lure (fake "Free_WiFi").
2. Captive portal: **generic credential-capture page** (email/password), posted + logged to the
   helper.
3. Internet egress: **capture-only first**; a "working" lure (routes via helper/Tor) comes later.
