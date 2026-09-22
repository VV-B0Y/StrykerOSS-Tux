package com.zalexdev.stryker.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.os.StatFs;

import com.zalexdev.stryker.utils.Core;

import java.io.File;

public final class VmSpecs {

    private VmSpecs() {}

    public static final String K_CPUS      = "rootless_cpus";
    public static final String K_RAM       = "rootless_ram";
    public static final String K_DISK_GB   = "rootless_disk_gb";
    public static final String K_CACHE     = "rootless_cache_mode";
    public static final String K_AIO       = "rootless_aio";
    public static final String K_MTTCG     = "rootless_mttcg";
    public static final String K_TBSIZE    = "rootless_tcg_tbsize";
    public static final String K_NO_RNG    = "rootless_no_rng";
    public static final String K_USB_OFF   = "rootless_usb_off";
    public static final String K_NO_SHARE  = "rootless_no_share";
    public static final String K_PAUTH     = "rootless_pauth";
    public static final String K_CPU_OK    = "rootless_cpu_verified";
    public static final String K_CPU_LEGACY = "rootless_cpu_legacy";
    public static final String K_NO_IOTHREAD = "rootless_no_iothread";
    public static final String K_NO_FASTBOOT = "rootless_no_fastboot";
    public static final String K_RESIZE_PENDING = "rootless_disk_resize_pending";
    public static final String K_IO_URING_OK = "rootless_io_uring_ok";
    public static final String K_SAFE_BOOT = "rootless_safe_boot";

    public static final long GB = 1024L * 1024L * 1024L;
    public static final long MB = 1024L * 1024L;

    public static final int DEFAULT_CPUS = 4;
    public static final int DEFAULT_RAM_MB = 4096;
    public static final int MIN_RAM_MB = 512;
    public static final int MIN_DISK_GB = 8;
    public static final int FLOOR_DISK_GB = 1;
    public static final int MAX_DISK_GB = 1024;
    public static final long DISK_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long DISK_GROW_STEP_BYTES = 2L * 1024L * 1024L * 1024L;


    public static int deviceCores() {
        int n = Runtime.getRuntime().availableProcessors();
        return n < 1 ? 1 : n;
    }

    public static int deviceRamMb(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (int) (mi.totalMem / MB);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean kvmAvailable() {
        File kvm = new File("/dev/kvm");
        return kvm.exists() && kvm.canWrite();
    }

    public static long internalFreeBytes(Context ctx) {
        try {
            StatFs s = new StatFs(ctx.getFilesDir().getAbsolutePath());
            return s.getAvailableBytes();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static long currentDiskBytes(Context ctx) {
        File f = RootlessPaths.rootfs(ctx);
        return f.exists() ? f.length() : 0;
    }

    public static long diskAllocatedBytes(Context ctx) {
        File f = RootlessPaths.rootfs(ctx);
        if (!f.exists()) return 0;
        try {
            long blocks = android.system.Os.stat(f.getAbsolutePath()).st_blocks;
            return blocks * 512L;
        } catch (Throwable t) {
            return f.length();
        }
    }


    public static int maxRamMb(Context ctx) {
        int dev = deviceRamMb(ctx);
        if (dev <= 0) return 4096;
        int reserve = Math.max(1024, dev / 4);
        return Math.max(MIN_RAM_MB, dev - reserve);
    }


    public static int actualDiskGb(Context ctx) {
        long cur = currentDiskBytes(ctx);
        if (cur <= 0) return 0;
        return (int) ((cur + GB - 1) / GB);
    }

    public static long fittingDiskBytes(Context ctx) {
        long allocated = diskAllocatedBytes(ctx);
        long free = internalFreeBytes(ctx);
        long headroom = Math.max(0L, free - DISK_RESERVE_BYTES);
        long fitting = ((allocated + headroom) / GB) * GB;
        return Math.min((long) MAX_DISK_GB * GB,
                Math.max((long) MIN_DISK_GB * GB, fitting));
    }

    public static long autoDiskTargetBytes(Context ctx) {
        long apparent = currentDiskBytes(ctx);
        return Math.min((long) MAX_DISK_GB * GB,
                Math.max(apparent, fittingDiskBytes(ctx)));
    }



    public static boolean shouldAutoGrow(Context ctx) {
        long current = currentDiskBytes(ctx);
        if (current <= 0) return false;
        return autoDiskTargetBytes(ctx) >= current + DISK_GROW_STEP_BYTES;
    }


    public static int recommendedCpus() {
        int c = deviceCores();
        if (c <= 0) return 1;
        return Math.min(DEFAULT_CPUS, c);
    }

    public static int recommendedRamMb(Context ctx) {
        return Math.min(DEFAULT_RAM_MB, maxRamMb(ctx));
    }


    public static int effectiveCpus(Context ctx, Core core) {
        return effectiveCpus(ctx, core, 0);
    }

    public static int effectiveCpus(Context ctx, Core core, int vmIndex) {
        int v = core.getInt(cpusKey(vmIndex), 0);
        if (v <= 0 && vmIndex == 0) v = core.getInt(K_CPUS, 0);  // legacy global pref = vm0
        if (v <= 0) v = vmIndex <= 0 ? recommendedCpus() : Math.max(1, recommendedCpus() / 2);
        return clamp(v, 1, deviceCores());
    }

    public static int effectiveRamMb(Context ctx, Core core) {
        return effectiveRamMb(ctx, core, 0);
    }

    public static int effectiveRamMb(Context ctx, Core core, int vmIndex) {
        int v = core.getInt(ramKey(vmIndex), 0);
        if (v <= 0 && vmIndex == 0) v = core.getInt(K_RAM, 0);  // legacy global pref = vm0
        if (v <= 0) v = vmIndex <= 0 ? recommendedRamMb(ctx) : Math.max(MIN_RAM_MB, recommendedRamMb(ctx) / 2);
        return clamp(v, MIN_RAM_MB, maxRamMb(ctx));
    }

    /** Per-VM pref keys (index-suffixed) so each VM keeps its own CPU/RAM. */
    public static String cpusKey(int vmIndex) { return K_CPUS + "_" + vmIndex; }
    public static String ramKey(int vmIndex) { return K_RAM + "_" + vmIndex; }

    public static void setCpus(Context ctx, Core core, int vmIndex, int cpus) {
        core.putInt(cpusKey(vmIndex), clamp(cpus, 1, deviceCores()));
    }

    public static void setRamMb(Context ctx, Core core, int vmIndex, int ramMb) {
        core.putInt(ramKey(vmIndex), clamp(ramMb, MIN_RAM_MB, maxRamMb(ctx)));
    }


    public static boolean safeBoot(Core core) {
        return core != null && core.getBoolean(K_SAFE_BOOT);
    }

    public static void setSafeBoot(Core core, boolean on) {
        if (core != null) core.putBoolean(K_SAFE_BOOT, on);
    }

    public static String cacheMode(Core core) {
        if (safeBoot(core)) return "writeback";
        String m = core.getString(K_CACHE);
        if (m == null || m.isEmpty()) return "writeback";
        switch (m) {
            case "unsafe": case "writethrough": case "none": case "writeback": return m;
            default: return "writeback";
        }
    }

    public static int ioUringState(Core core) {
        return core == null ? 0 : core.getInt(K_IO_URING_OK, 0);
    }

    public static void setIoUringState(Core core, boolean ok) {
        if (core != null) core.putInt(K_IO_URING_OK, ok ? 1 : -1);
    }

    public static String aioMode(Core core) {
        if (safeBoot(core)) return "threads";
        if (!"io_uring".equals(core.getString(K_AIO))) return "threads";
        return ioUringState(core) > 0 ? "io_uring" : "threads";
    }

    public static String requestedAioMode(Core core) {
        return "io_uring".equals(core.getString(K_AIO)) ? "io_uring" : "threads";
    }

    public static boolean mttcg(Core core) {
        if (!core.contains(K_MTTCG)) return true;
        return core.getBoolean(K_MTTCG);
    }

    public static int tbSizeMb(Context ctx, Core core, int ramMb) {
        int v = core.getInt(K_TBSIZE, 0);
        if (v > 0) return v;
        return ramMb >= 2048 ? 512 : 256;
    }

    public static String pauthMode(Core core) {
        if (safeBoot(core)) return "impdef";
        String m = core == null ? null : core.getString(K_PAUTH);
        if (m == null || m.isEmpty()) return "off";
        switch (m) {
            case "off": case "impdef": case "qarma": return m;
            default: return "off";
        }
    }

    public static final String LEGACY_CPU = "max,sve=off,pauth-impdef=on";

    public static String cpuModel(Core core) {
        if (safeBoot(core) || (core != null && core.getBoolean(K_CPU_LEGACY))) return LEGACY_CPU;
        switch (pauthMode(core)) {
            case "qarma":  return "max,sve=off,pmu=off,pauth=on,pauth-impdef=off";
            case "impdef": return "max,sve=off,pmu=off,pauth=on,pauth-impdef=on";
            default:       return "max,sve=off,pmu=off,pauth=off";
        }
    }

    public static boolean ioThread(Core core) {
        return !safeBoot(core) && !core.getBoolean(K_NO_IOTHREAD);
    }

    public static boolean fastBoot(Core core) {
        return !safeBoot(core) && !core.getBoolean(K_NO_FASTBOOT);
    }

    public static boolean rngEnabled(Core core)   { return !safeBoot(core) && !core.getBoolean(K_NO_RNG); }
    public static boolean usbEnabled(Core core)   { return !core.getBoolean(K_USB_OFF); }
    public static boolean shareEnabled(Core core) { return !safeBoot(core) && !core.getBoolean(K_NO_SHARE); }


    public static String humanBytes(long bytes) {
        if (bytes >= GB) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) GB);
        if (bytes >= MB) return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) MB);
        return bytes + " B";
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
