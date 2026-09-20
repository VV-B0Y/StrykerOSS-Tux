# Dual-Engine (Root + Rootless) Coexistence

Status: approved design, in progress.

## Problem

Stryker currently forces an exclusive engine choice at first run. `EngineType` is a
single persisted value (`CHROOT` or `ROOTLESS`), onboarding installs exactly one engine,
and every tool routes through `Core.isRootless()` — a boolean that reads that one choice.
A rooted device cannot use the rootless VM without wiping the choice, and vice versa.

## Goal

Let the user install and use BOTH engines side by side in the same interface:

- Onboarding offers both as independent checkboxes; either can be added later from the dashboard.
- The dashboard shows TWO engine cards (Chroot + Rootless VM), each with its own status and controls.
- A runtime selector (chroot / VM toggle) chooses which engine shared tools target.
- Root-only tools (HID Attacks, USB Arsenal, MAC changer) stay bound to root hardware and
  lock when root is unavailable — they are not affected by the runtime selector.

## Key facts about the current code (verified by reading)

- `EngineType` (CHROOT/ROOTLESS) is the "which engine" preference, key `engine_type`.
- `Core.isRootless()` -> `EngineType.isRootless(this)` -> active engine is ROOTLESS.
- The backend seam already exists and is clean:
  - `Core.customChrootCommand(cmd)` routes to `rootless().exec(cmd)` when active is VM,
    otherwise runs the same command through the chroot via `su`.
  - `Core.customCommand(cmd)` always runs on the host via `su` (mounts, /proc, file checks).
  - So "active engine == VM" is exactly the signal the ~40 `isRootless()` call sites need
    for guest-command routing. Those call sites do NOT need per-site changes.
- `Core.checkRoot()` -> `id` contains `uid=0` (root available on the host).
- Chroot presence is a plain host file: `Core.CHROOT_MARKER` = `/data/local/stryker/release/6.0`.
- VM presence: `RootlessEngine.isInstalled()` (qemu bin + kernel + initrd + libslirp + rootfs).
- Dashboard `setupVmCard()` currently morphs ONE card (`vm_card`) into either a full VM card
  or a reduced "Chroot engine" card.
- MainActivity `runLaunchFlow()` is either/or; drawer gating uses `ROOT_ONLY_IDS`
  (HID, USB Arsenal, MAC changer) locked in rootless mode.

## Design

### Data model

- `EngineType` keeps its meaning as the ACTIVE engine (CHROOT/ROOTLESS). `isRootless()`
  keeps meaning "active == ROOTLESS" so existing call sites stay correct.
- Add availability (distinct from active), derived, no new persisted state:
  - `EngineType.chrootAvailable(core)` = root present (`checkRoot`) AND `CHROOT_MARKER` exists.
  - `EngineType.rootlessAvailable(core)` = `RootlessEngine.isInstalled()`.
- Add `Core.chrootInstalled()` (host-side `File(CHROOT_MARKER).isFile()`, no su) and
  `Core.vmInstalled()`.

### Onboarding

Flow becomes: CONSENT -> ENGINE (multi-select) -> PERMS -> [PCHECK + INSTALL_CHROOT if chroot]
-> [INSTALL_QEMU if VM] -> FINAL.

- `SlideEngineSelect` becomes two independent checkboxes (Root / Rootless). Chroot is
  disabled with a note when root is unavailable; Rootless disabled when not arm64.
- `AppIntroActivity.applyEngineFlow(Set<EngineType>)` builds install pages for every
  selected engine.
- Nothing selected -> Continue disabled.

### Runtime (dashboard + drawer)

- Dashboard shows two cards side by side (or stacked): Chroot card (status mount/unmount)
  and the existing VM card (status/start/stop/stats/logs/USB).
- A segmented "active engine" toggle (chroot / VM) persists `engine_type` and is the source
  of truth for `isRootless()`.
- Drawer gating changes:
  - Root-only tools lock when `!rootAvailable()` (not "rootless").
  - Shared engine tools lock when the ACTIVE engine is not ready (VM not READY, or chroot
    not mounted).
- `EngineStatus` gains per-engine helpers so both cards render independently.

### MainActivity launch

- Land on the dashboard whenever either engine is available; start the VM service if the VM
  is installed, mount the chroot if root is available. No exclusive branch.

## Work breakdown (issues)

1. Engine model: availability helpers + `Core.chrootInstalled()/vmInstalled()`.
2. Onboarding multi-select install flow.
3. Dashboard dual-card + runtime selector UI.
4. MainActivity launch flow + drawer gating.
5. EngineStatus per-engine + Slide6Final copy.

## Verification

- `./gradlew assembleDebug` compiles clean.
- Install on the connected device (Motorola Edge Plus 2023, rooted) and smoke-test:
  onboarding shows both checkboxes, dashboard shows both cards, runtime toggle flips
  guest-command routing, root-only tools lock when root is absent.

## Out of scope (this change)

- QEMU VM backend improvements, in-the-loop agent, deauth policy — all separate concerns.
