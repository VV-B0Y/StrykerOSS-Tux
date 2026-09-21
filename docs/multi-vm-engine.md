# Multi-VM Rootless Engine — Design Plan

Status: helper-VM role locked; engine foundation + dashboard implemented (commits `8b5b2bd`..`dd0c3fe`).

## Requirements (from user)

1. Multiple VMs running at a time — **limit 2 VMs total, 2 running simultaneously**.
2. **Share networking OR act completely independently** — per-VM networking mode: bridged onto
   the host network (shared) or isolated NAT (independent).
3. **Act independently** — separate disk, separate QEMU process, separate state; one VM's
   crash or stop must not affect the other.
4. **Clone a VM** — copy an existing VM's disk to a new VM.
5. **Create a new VM from the existing install script** — re-run provisioning to a fresh slot.
6. **Per-VM kernel (and initrd) support** — a VM may override the default kernel/initrd.
7. **Helper VM (sidecar)** — the second VM is a lean offsec helper / obfuscation layer for
   vm0, not a second desktop (section below).

## Helper VM (sidecar) — role

vm1 is not a second full machine. It is a lightweight sidecar that serves as an offsec
helper + obfuscation layer for vm0:

- **OS**: the **same Stryker image** (same base rootfs as vm0), booted headless as a server.
- **Keep the Realtek (RTL8812AU) driver intact** so it can put the external USB adapter into
  monitor mode and capture WPA handshakes (airodump-ng/aircrack-ng). The strip-to-server step
  must NOT remove the Realtek driver or the aircrack tooling.
- **Roles**: (1) relay vm0's traffic (redirector/obfuscation), (2) capture handshakes off the
  external adapter, (3) host payloads (HTTP), (4) DNS / C2 relay.
- **Egress**: Tor (or a user VPN) so the relay actually changes the egress IP.
- **Resources**: half the autotuned RAM/CPU (already implemented).
- **WiFi capture**: the external RTL8812AU dongle is passed into the helper (USB passthrough,
  exclusive — vm0 and the helper cannot both hold it at once).

Provisioning the helper = clone the base template (same as vm0) + a **helper-role setup** inside
the new VM: boot headless, install server tooling (tor, nginx, dnsmasq, tcpdump, tshark, socat),
and KEEP the Realtek driver + aircrack-ng. "Strip to server" removes the desktop, not the drivers.

Networking: a private VM↔VM link (QEMU socket netdev pair) so vm0 can route through the helper;
the helper runs Tor (SOCKS) as the relay. This makes the formerly-deferred "shared networking"
the point of the helper.

## Current architecture (single-VM)

- `RootlessPaths` — one fixed dir `files/rootless/` holding qemu, libslirp, `Image`,
  `initrd.img`, `rootfs.img`, unix sockets (`qmp.sock`, `serial.sock`, `term.sock`), logs,
  and a `.active` flag. All paths are static, VM-agnostic.
- `RootlessEngine` — process-wide singleton (`RootlessEngine.get(context)`); boots ONE VM,
  owns the QEMU process, QMP/serial sockets, disk resize, guest-core deploy.
- Fixed host-forward ports baked in as constants: exec 1050, term 1051, pty 1052,
  capture 1053, ssh 2222.
- `GuestExec`, `GuestConsole`, `QmpClient`, `UsbPassthroughManager` all read the fixed
  `RootlessPaths` ports/socket — no notion of "which VM".
- `Core.rootless()` → `RootlessEngine.get(context)`; ~46 call sites.

## Target architecture

### Storage layout

```
files/rootless/
  bin/                     shared, installed once
    qemu-system-aarch64
    libslirp.so
  boot/                    shared defaults (per-VM override lives in the VM dir)
    Image
    initrd.img
  vms/
    <id>/                  per-VM (id is stable, e.g. "vm0", "vm1")
      rootfs.img
      Image                optional per-VM kernel override
      initrd.img           optional per-VM initrd override
      qmp.sock  serial.sock  term.sock
      boot.log  serial.log
      .active
      vm.json              { name, portOffset, specs }
  registry.json            [{ id, name, created, sizeBytes }]
```

Rationale:
- qemu + libslirp are identical across VMs → install once into `bin/`, never re-download.
- kernel/initrd are shared by default but a VM can carry its own copy → per-VM override wins.
- `rootfs.img`, sockets, logs, config are per-VM.

### Port scheme

Each VM gets a host `portOffset = vmIndex * 1000`:

| purpose | guest port | VM0 host (offset 0) | VM1 host (offset 1000) |
|---|---|---|---|
| guest exec  | 1050 | 1050 | 2050 |
| guest term  | 1051 | 1051 | 2051 |
| guest pty   | 1052 | 1052 | 2052 |
| guest capture | 1053 | 1053 | 2053 |
| guest ssh   | 22   | 2222 | 2223 |

VM0 keeps today's ports (no migration). Guest ports stay constant inside each VM (each VM's
SLIRP namespace is isolated, so 1050 inside VM0 and 1050 inside VM1 do not collide).

### Engine

- `RootlessEngine` becomes **per-VM** (one instance per VM), still owning its own QEMU process,
  sockets, disk, and status.
- New `VmRegistry` (or `VmManager`) holds the ordered list of up to 2 VMs, each with its own
  `RootlessEngine`, port offset, paths, and persisted spec.
- Replace `Core.rootless()` with `Core.vm(String id)` and `Core.vms()`; migrate the ~46 call
  sites to address a specific VM (dashboard and settings operate on the "selected" VM).

### Networking (per-VM mode: shared or independent)

Each VM picks a networking mode, independently of the other:

- **Independent (isolated NAT)** — `-netdev user` (SLIRP). The VM reaches the internet through
  NAT but is invisible to the LAN and to the other VM. This is today's behavior and the default.

- **Shared (bridged onto the host network)** — the VM attaches to a host tap + bridge (needs
  root, which the app holds), so it sits on the same L2/L3 network as the phone and any other
  VM in shared mode: it gets a LAN IP and can reach LAN hosts and the other VM directly. This is
  the mode for LAN pentest work.

Two VMs in "shared" mode see each other on the bridge. A VM in "independent" mode stays
isolated from everything except the host-forwarded ports. Bridged mode is the heavier piece
(tap + bridge setup + DHCP/static addressing), so it is its own phase.

### USB passthrough

- A physical USB device attaches to **one VM at a time** (hardware constraint — a device
  cannot be passed to two VMs concurrently). Attach/detach is routed to the owning VM's QMP
  socket; attaching to VM1 fails cleanly if the device is held by VM0.

### Guest I/O routing

- `GuestExec`, `GuestConsole`, `QmpClient`, `UsbPassthroughManager` take a VM id (or engine)
  and read that VM's port offset / socket path instead of the static constants.

### Installer / install script

- Shared artifacts (qemu, libslirp, default kernel/initrd) install once into `bin/` + `boot/`.
- **New VM**: provision `vms/<id>/rootfs.img` (fresh download or copy of a base template) and
  write `vm.json`; optional per-VM kernel/initrd dropped into the VM dir.
- **Clone VM**: `cp vms/<src>/rootfs.img vms/<dst>/rootfs.img` (+ copy kernel/initrd override
  if the source has one), then register the new id.

## Phases

1. **Abstraction** — introduce `VmId`; parameterize `RootlessPaths` (per-VM dirs + ports).
   VM0 ("default") keeps current behavior; no user-visible change.
2. **Registry + engine pool + per-VM prefs** — `VmRegistry`, up to 2 `RootlessEngine`s,
   namespaced `VmSpecs`.
3. **Guest I/O parameterization** — `GuestExec`/`GuestConsole`/`QmpClient`/`UsbPassthrough`
   route to a VM id.
4. **Installer/install-script parameterization** — new-VM + clone provisioning.
5. **UI** — VM manager (list, add, clone, delete, rename, per-VM start/stop/status on the
   dashboard); VNC/terminal address a selected VM.
6. **Shared (bridged) networking** — tap + bridge setup, per-VM "shared" mode toggle.

## Decisions (locked)

1. Bridged ("shared") networking: **independent-only first**, shared mode next (Phase 6).
2. VM naming: **free-text**, default "VM 1"/"VM 2"; stable internal id stays `vm0`/`vm1`.
3. Disk size: **custom value allowed when creating a VM not from the Stryker template**
   (clone / imported image); a Stryker-template VM uses the template's default size.
4. Second VM RAM/CPU: **half the autotuned value**.
5. Helper VM OS: **the same Stryker image** (not a separate minimal OS).
6. Helper VM keeps the **Realtek (RTL8812AU) driver** intact for external-adapter handshake
   capture; strip-to-server must preserve it + aircrack tooling.
7. Helper roles: **relay + handshake capture + payload host + DNS/C2**; egress via **Tor** now,
   VPN pluggable later.
8. Helper handshake capture is via the **external USB adapter** (RTL8812AU) passed into the
   helper — the internal chip stays host-side.
