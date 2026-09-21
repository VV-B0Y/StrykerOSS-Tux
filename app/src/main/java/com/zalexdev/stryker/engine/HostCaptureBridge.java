package com.zalexdev.stryker.engine;

import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;

/**
 * HostCaptureBridge: exposes the host's internal-chip passive capture to the rootless VM.
 *
 * The phone's internal Qualcomm radio is PCIe, so QEMU can't pass it through. Instead the host
 * enables monitor mode on it (vendor con_mode parameter), runs a capture tool inside the chroot,
 * and exposes the output through the 9p share the guest already mounts at /sdcard/Stryker.
 *
 * The chroot's /data is a separate tree, so a bind-mount bridges the two: the host raw path under
 * /data/media/0 (which maps to the guest's 9p view) is bind-mounted into the chroot at /data/bridge.
 * airodump-ng writes there; the guest reads the same bytes through 9p.
 *
 * Fallbacks:
 *   - monitor mode: kiwi_v2 (FastConnect 7800/WCN7851) then wlan (older qcacld)
 *   - capture tool: airodump-ng (parsed AP list CSV + pcap) then tcpdump (raw pcap only)
 *
 * The reverse direction (a USB adapter captured inside the VM and streamed to the chroot) is the
 * existing port-1053 relay.
 */
public class HostCaptureBridge {

    /** Monitor-mode enable/disable via the vendor con_mode parameter (kiwi_v2 or wlan). */
    public static final String CON_MODE_4 =
            "echo 4 > /sys/module/kiwi_v2/parameters/con_mode 2>/dev/null || echo 4 > /sys/module/wlan/parameters/con_mode";
    public static final String CON_MODE_0 =
            "echo 0 > /sys/module/kiwi_v2/parameters/con_mode 2>/dev/null || echo 0 > /sys/module/wlan/parameters/con_mode";

    /** Host raw path root can write; the guest reads this via 9p as /sdcard/Stryker/captured/bridge. */
    public final String rawDir;
    /** Host raw path of the app-visible captured/ folder (root must write here, not the FUSE path). */
    public final String rawCaptured;
    /** Chroot path that is bind-mounted onto rawDir. */
    public static final String CHROOT_DIR = "/data/bridge";

    private static final String CHROOT = "/data/local/stryker/release";

    private final Core core;
    private volatile boolean running = false;

    public HostCaptureBridge(Core core) {
        this.core = core;
        if (core.isRootless()) {
            // Rootless: the share is the app-scoped 9p dir; the app can't read root-written files
            // there via FUSE, so every read/write goes through su on the raw path.
            rawDir = "/data/media/0/Android/data/com.zalexdev.stryker/files/Stryker/captured/bridge";
            rawCaptured = "/data/media/0/Android/data/com.zalexdev.stryker/files/Stryker/captured";
        } else {
            // Chroot: the share is public external storage (/storage/emulated/0/Stryker).
            rawDir = "/data/media/0/Stryker/captured/bridge";
            rawCaptured = "/data/media/0/Stryker/captured";
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Enable internal-chip monitor mode and start capturing into the share. */
    public boolean start() {
        if (running) return true;

        // 1. Enable monitor mode on the internal radio.
        core.customCommandSuC("svc wifi disable; " + CON_MODE_4 + "; ip link set wlan0 up");

        if (core.isRootless()) {
            // 2. Prepare the share dir and bind-mount it into the chroot.
            core.customCommandSuC("mkdir -p " + rawDir + "; rm -f " + rawDir + "/*; mkdir -p " + CHROOT + CHROOT_DIR + "; "
                    + "umount " + CHROOT + CHROOT_DIR + " 2>/dev/null; "
                    + "mount --bind " + rawDir + " " + CHROOT + CHROOT_DIR);
            // 3. Capture: airodump-ng (parsed CSV + pcap) inside the chroot, tcpdump (pcap) as fallback.
            String capture = "if [ -x " + CHROOT + "/sbin/airodump-ng ]; then "
                    + "nohup chroot " + CHROOT + " /sbin/airodump-ng wlan0 --band abg --write " + CHROOT_DIR
                    + "/cap --output-format pcap,csv --update 1 >/dev/null 2>&1 & "
                    + "else nohup /system/bin/tcpdump -i wlan0 -w " + rawDir + "/cap.pcap -U >/dev/null 2>&1 & fi";
            core.customCommandSuC(capture);
        } else {
            // Chroot mode: write through the chroot's /sdcard FUSE mount (public storage) — no
            // bind-mount needed, and root cannot write /data/media/0/Stryker directly.
            core.customChrootCommand("mkdir -p /sdcard/Stryker/captured/bridge; rm -f /sdcard/Stryker/captured/bridge/*");
            core.customCommandSuC("nohup chroot " + CHROOT + " /sbin/airodump-ng wlan0 --band abg "
                    + "--write /sdcard/Stryker/captured/bridge/cap --output-format pcap,csv --update 1 "
                    + ">/dev/null 2>&1 &");
        }

        running = true;
        return true;
    }

    /** Stop capturing, tear down the bind-mount (rootless), and restore normal Wi-Fi. */
    public void stop() {
        running = false;
        core.customCommandSuC("killall airodump-ng 2>/dev/null; killall tcpdump 2>/dev/null; true");
        if (core.isRootless()) {
            core.customCommandSuC("umount " + CHROOT + CHROOT_DIR + " 2>/dev/null; true");
        }
        core.customCommandSuC(CON_MODE_0 + "; svc wifi enable");
    }

    /** Move the finished capture into the app-visible captured/ dir. Returns true on success. */
    public boolean save(String destName) {
        if (core.isRootless()) {
            core.customCommandSuC("mkdir -p " + rawCaptured);
            core.customCommandSuC("mv " + rawDir + "/cap-01.cap " + rawCaptured + "/" + destName);
        } else {
            // Chroot: root can't write /data/media/0/Stryker, so mv through the chroot's /sdcard FUSE.
            core.customChrootCommand("mkdir -p /sdcard/Stryker/captured; "
                    + "mv /sdcard/Stryker/captured/bridge/cap-01.cap /sdcard/Stryker/captured/" + destName);
        }
        return fileSize(rawCaptured + "/" + destName) > 0;
    }

    /** True when the chroot has airodump-ng (passive capture with parsed AP list is available). */
    public boolean hasAirodump() {
        ArrayList<String> out = core.customCommandSuC(
                "chroot " + CHROOT + " /bin/sh -c 'test -x /sbin/airodump-ng -o -x /usr/sbin/airodump-ng' "
                        + "&& echo yes || echo no");
        return !out.isEmpty() && out.get(0).trim().equals("yes");
    }

    /** Host-direct managed scan (no monitor mode, no chroot): returns `iw dev wlan0 scan` output. */
    public ArrayList<String> hostScan() {
        return core.customCommandSuC("iw dev wlan0 scan 2>&1");
    }

    /** Host-direct packet capture (monitor mode + tcpdump), no chroot dependency. */
    public boolean startHostCapture() {
        if (running) return true;
        core.customCommandSuC("svc wifi disable; " + CON_MODE_4 + "; ip link set wlan0 up");
        core.customCommandSuC("mkdir -p " + rawDir + "; rm -f " + rawDir + "/*");
        core.customCommandSuC("nohup /system/bin/tcpdump -i wlan0 -w " + rawDir + "/cap.pcap -U >/dev/null 2>&1 &");
        running = true;
        return true;
    }

    /** The .cap/.pcap file the capture landed in (raw host path). */
    public String captureFileRaw() {
        return rawDir + "/cap-01.cap";
    }

    /** Packet count in a pcap file (via tcpdump -r), -1 on failure/empty. */
    public int packetCount(String pcapPath) {
        ArrayList<String> out = core.customCommandSuC(
                "/system/bin/tcpdump -r " + pcapPath + " -nn 2>/dev/null | wc -l");
        if (out.isEmpty()) return -1;
        try {
            return Integer.parseInt(out.get(0).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Byte size of a host file (via wc -c), -1 on failure. */
    public long fileSize(String path) {
        ArrayList<String> out = core.customCommandSuC("wc -c " + path + " 2>/dev/null");
        if (out.isEmpty()) return -1;
        String[] p = out.get(0).trim().split("\\s+");
        if (p.length == 0) return -1;
        try {
            return Long.parseLong(p[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
