package com.zalexdev.stryker.engine;

import com.zalexdev.stryker.utils.Core;

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
    public static final String RAW_DIR =
            "/data/media/0/Android/data/com.zalexdev.stryker/files/Stryker/captured/bridge";
    /** Chroot path that is bind-mounted onto RAW_DIR. */
    public static final String CHROOT_DIR = "/data/bridge";

    private static final String CHROOT = "/data/local/stryker/release";

    private final Core core;
    private volatile boolean running = false;

    public HostCaptureBridge(Core core) {
        this.core = core;
    }

    public boolean isRunning() {
        return running;
    }

    /** Enable internal-chip monitor mode and start capturing into the 9p share. */
    public boolean start() {
        if (running) return true;

        // 1. Enable monitor mode on the internal radio.
        core.customCommand("svc wifi disable; " + CON_MODE_4 + "; ip link set wlan0 up", true);

        // 2. Prepare the share dir and bind-mount it into the chroot.
        core.customCommand("mkdir -p " + RAW_DIR + "; rm -f " + RAW_DIR + "/*; mkdir -p " + CHROOT + CHROOT_DIR + "; "
                + "umount " + CHROOT + CHROOT_DIR + " 2>/dev/null; "
                + "mount --bind " + RAW_DIR + " " + CHROOT + CHROOT_DIR, true);

        // 3. Capture: airodump-ng (parsed CSV + pcap) inside the chroot, tcpdump (pcap) as fallback.
        String capture = "if chroot " + CHROOT
                + " /bin/sh -c 'test -x /sbin/airodump-ng -o -x /usr/sbin/airodump-ng' >/dev/null 2>&1; then "
                + "chroot " + CHROOT + " /bin/sh -c 'export PATH=/usr/sbin:/usr/bin:/sbin:/bin; "
                + "nohup airodump-ng wlan0 --write " + CHROOT_DIR + "/cap --output-format pcap,csv --update 1 "
                + ">/dev/null 2>&1 &'; "
                + "else "
                + "nohup /system/bin/tcpdump -i wlan0 -w " + RAW_DIR + "/cap.pcap -U >/dev/null 2>&1 &; "
                + "fi";
        core.customCommand(capture, true);

        running = true;
        return true;
    }

    /** Stop capturing, tear down the bind-mount, and restore normal Wi-Fi. */
    public void stop() {
        running = false;
        core.customCommand("killall airodump-ng 2>/dev/null; killall tcpdump 2>/dev/null; true", true);
        core.customCommand("umount " + CHROOT + CHROOT_DIR + " 2>/dev/null; true", true);
        core.customCommand(CON_MODE_0 + "; svc wifi enable", true);
    }
}
