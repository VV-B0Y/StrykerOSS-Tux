# VM Management — Enterprise-Solid Multi-VM

## Goal
Manage several VMs (max **2 running simultaneously**), with per-VM terminal access so
each VM can be configured for a different use.

## Decisions (signed off 2026-09-22)
- **Terminal access:** "Open terminal" per VM in the management screen **and** a VM
  picker inside the terminal. The left drawer's "Terminal" item opens the terminal of
  the *currently-selected* VM (or the chroot when in chroot mode).
- **Management UI:** a full dedicated screen (fragment) with live status, per-VM
  config, logs, and actions — replaces the current throwaway dialog.
- **2-VM cap:** hard-enforced in code — starting a 3rd VM while two are running is
  blocked.

## Current state (already built)
- `RootlessEngine(vmId, vmIndex)` — per-VM engine instances.
- `RootlessPaths` — per-VM ports: vm0 = 1050/1051/1052/1053/2222, vm1 = 2050/2051/2052/2053/2223.
- `VmRegistry` (`registry.json`) — list/create/clone/snapshot.
- `VmSpecs` + `VmSettingsFragment` — per-VM CPU/RAM/disk/cache tuning.
- Drawer "VM management" — a basic dialog (list + clone/snapshot/reset/delete/create).
- Dashboard `VmCard` — start/stop + specs line.
- **Gap:** `NeoTermActivity.addNewStrykerSession` hardcodes `vm0` (`pty:127.0.0.1:1051`).

## Milestones
1. **Per-VM terminal + selected-VM concept.** A single source of truth for "which VM
   is selected"; the terminal opens vm0 *or* vm1 (or the chroot); the drawer's
   "Terminal" opens the selected VM; a VM picker in the terminal switches sessions.
2. **VM management screen.** A fragment replacing the drawer dialog: live VM list with
   status (running/stopped/booting), per-VM config + logs, and actions
   (start/stop/clone/snapshot/reset/delete/create/open-terminal).
3. **2-VM cap + simultaneous-run hardening.** Hard-enforce the cap; verify running two
   VMs at once is solid end-to-end (ports, QMP, exec, terminal).

## Definition of done
- Create two VMs, run both simultaneously, open each one's terminal.
- Start/stop/clone/snapshot/reset/delete from the management screen with live status.
- A 3rd VM start is blocked while two are running.
- The drawer "Terminal" item opens the selected VM (or chroot).
