package com.zalexdev.stryker.engine;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.zalexdev.stryker.ota.QemuDownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

public final class QemuInstaller {

    private static final String TAG = "QemuInstaller";
    private static final String ASSET_DIR = "rootless";

    public interface Progress {
        void onStage(Stage stage);
        void onBytes(String label, long done);
        void onLog(int level, String message);
    }

    public enum Stage {
        PREPARING("Preparing"),
        EXTRACTING_QEMU("Extracting QEMU"),
        EXTRACTING_KERNEL("Extracting kernel"),
        EXTRACTING_LIBS("Extracting libraries"),
        DECOMPRESSING_ROOTFS("Decompressing rootfs"),
        FINALIZING("Finalizing"),
        DONE("Done");

        public final String title;
        Stage(String title) { this.title = title; }
    }

    private QemuInstaller() {}

    private static String rootfsAssetName(Context c) {
        try {
            String[] files = c.getAssets().list(ASSET_DIR);
            if (files == null) return null;
            String imgz = null, gz = null, raw = null;
            for (String f : files) {
                if (f.equals("rootfs.imgz")) imgz = f;
                else if (f.equals("rootfs.img.gz")) gz = f;
                else if (f.equals("rootfs.img")) raw = f;
            }
            if (imgz != null) return imgz;
            if (gz != null) return gz;
            return raw;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isCompressed(String assetName) {
        return assetName != null && (assetName.endsWith(".imgz") || assetName.endsWith(".gz"));
    }

    public static boolean assetsPresent(Context c) {
        try {
            String[] files = c.getAssets().list(ASSET_DIR);
            if (files == null) return false;
            boolean q = false, k = false, l = false, ird = false;
            for (String f : files) {
                if (f.equals("qemu-system-aarch64")) q = true;
                else if (f.equals("Image")) k = true;
                else if (f.equals("libslirp.so")) l = true;
                else if (f.equals("initrd.img")) ird = true;
            }
            return q && k && l && ird && rootfsAssetName(c) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean install(Context context, Progress p) {
        if (!assetsPresent(context)) {
            log(p, 1, "No bundled artifacts — fetching the engine from the release");
            return installFromNetwork(context, p);
        }
        return installFromAssets(context, p);
    }

    private static boolean installFromAssets(Context context, Progress p) {
        AssetManager am = context.getAssets();
        File base = RootlessPaths.base(context);
        try {
            stage(p, Stage.PREPARING);
            if (!base.exists() && !base.mkdirs()) {
                log(p, 3, "Cannot create " + base.getAbsolutePath());
                return false;
            }

            stage(p, Stage.EXTRACTING_QEMU);
            copyAsset(am, ASSET_DIR + "/qemu-system-aarch64", RootlessPaths.qemuBin(context), p, "QEMU");
            RootlessPaths.qemuBin(context).setExecutable(true, false);

            stage(p, Stage.EXTRACTING_KERNEL);
            copyAsset(am, ASSET_DIR + "/Image", RootlessPaths.kernel(context), p, "kernel");
            copyAsset(am, ASSET_DIR + "/initrd.img", RootlessPaths.initrd(context), p, "initrd");

            stage(p, Stage.EXTRACTING_LIBS);
            copyAsset(am, ASSET_DIR + "/libslirp.so", RootlessPaths.libslirp(context), p, "libslirp.so");

            stage(p, Stage.DECOMPRESSING_ROOTFS);
            String rootfsAsset = rootfsAssetName(context);
            if (rootfsAsset == null) {
                log(p, 3, "rootfs asset not found in assets/rootless");
                return false;
            }
            if (isCompressed(rootfsAsset)) {
                log(p, 1, "Decompressing " + rootfsAsset + " (this can take a minute)");
                gunzipAsset(am, ASSET_DIR + "/" + rootfsAsset, RootlessPaths.rootfs(context), p);
            } else {
                log(p, 1, "Copying " + rootfsAsset + " (already decompressed by the build)");
                copyAsset(am, ASSET_DIR + "/" + rootfsAsset, RootlessPaths.rootfs(context), p, "rootfs.img");
            }

            stage(p, Stage.FINALIZING);
            ensureMinimumDisk(context, p);
            boolean ok = RootlessEngine.get(context).isInstalled();
            if (ok) {
                stage(p, Stage.DONE);
                log(p, 2, "Rootless engine installed");
            } else {
                log(p, 3, "Post-install verification failed");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            log(p, 3, "Install error: " + e.getMessage());
            return false;
        }
    }

    private static boolean installFromNetwork(Context context, Progress p) {
        File base = RootlessPaths.base(context);
        try {
            stage(p, Stage.PREPARING);
            if (!base.exists() && !base.mkdirs()) {
                log(p, 3, "Cannot create " + base.getAbsolutePath());
                return false;
            }
            QemuDownloader.Bundle b = QemuDownloader.resolve(context);

            stage(p, Stage.EXTRACTING_QEMU);
            if (!fetch(b.qemu, RootlessPaths.qemuBin(context), "QEMU", p)) return false;
            RootlessPaths.qemuBin(context).setExecutable(true, false);

            stage(p, Stage.EXTRACTING_KERNEL);
            if (!fetch(b.kernel, RootlessPaths.kernel(context), "kernel", p)) return false;
            if (!fetch(b.initrd, RootlessPaths.initrd(context), "initrd", p)) return false;

            stage(p, Stage.EXTRACTING_LIBS);
            if (!fetch(b.libslirp, RootlessPaths.libslirp(context), "libslirp.so", p)) return false;

            stage(p, Stage.DECOMPRESSING_ROOTFS);
            File rootfs = RootlessPaths.rootfs(context);
            boolean compressed = b.rootfs != null && b.rootfs.url != null
                    && (b.rootfs.url.endsWith(".imgz") || b.rootfs.url.endsWith(".gz"));
            if (!compressed) {
                if (!fetch(b.rootfs, rootfs, "rootfs.img", p)) return false;
            } else {
                File archive = new File(base, "rootfs.download");
                if (!fetch(b.rootfs, archive, "rootfs", p)) return false;
                log(p, 1, "Decompressing rootfs (this can take a minute)");
                if (!gunzipFile(archive, rootfs, p)) {
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }

            stage(p, Stage.FINALIZING);
            ensureMinimumDisk(context, p);
            boolean ok = RootlessEngine.get(context).isInstalled();
            if (ok) {
                stage(p, Stage.DONE);
                log(p, 2, "Rootless engine installed");
            } else {
                log(p, 3, "Post-install verification failed");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "network install failed", e);
            log(p, 3, "Install error: " + e.getMessage());
            return false;
        }
    }

    private static boolean fetch(com.zalexdev.stryker.ota.RemoteManifest.Asset asset, File dest,
                                 String label, Progress p) {
        if (asset == null || !asset.isUsable()) {
            log(p, 3, label + ": no download URL in the manifest");
            return false;
        }
        log(p, 1, "GET " + asset.url);
        com.zalexdev.stryker.ota.VerifiedDownloader.Result r =
                com.zalexdev.stryker.ota.VerifiedDownloader.download(
                        asset.url, dest, asset.sha256, asset.size,
                        (done, total) -> { if (p != null) p.onBytes(label, done); });
        if (!r.ok) {
            log(p, 3, label + ": " + r.error);
            return false;
        }
        if (asset.sha256 == null || asset.sha256.isEmpty()) {
            log(p, 3, label + " downloaded but the manifest carries no checksum");
        }
        log(p, 2, label + " ready (" + mb(dest.length()) + ")");
        return true;
    }

    private static boolean gunzipFile(File src, File dest, Progress p) {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        long total = 0;
        try (GZIPInputStream in = new GZIPInputStream(new java.io.FileInputStream(src), 1 << 16);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int read;
            long lastReport = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
                if (total - lastReport > 16 * 1024 * 1024) {
                    lastReport = total;
                    if (p != null) p.onBytes("rootfs.img", total);
                }
            }
            out.flush();
        } catch (IOException e) {
            log(p, 3, "Decompression failed: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) {
                log(p, 3, "rename failed for " + dest);
                return false;
            }
        }
        log(p, 2, "rootfs.img ready (" + mb(total) + ")");
        return true;
    }

    private static void ensureMinimumDisk(Context context, Progress p) {
        try {
            File img = RootlessPaths.rootfs(context);
            if (!img.exists()) return;
            long target = Math.min((long) VmSpecs.MIN_DISK_GB * VmSpecs.GB,
                    VmSpecs.autoDiskTargetBytes(context));
            if (img.length() >= target) return;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
                raf.setLength(target);
                raf.getFD().sync();
            }
            if (img.length() < target) {
                log(p, 3, "Could not reserve " + (target / VmSpecs.GB) + " GB for the VM disk");
                return;
            }
            com.zalexdev.stryker.utils.Core core = new com.zalexdev.stryker.utils.Core(context);
            core.putBoolean(VmSpecs.K_RESIZE_PENDING, true);
            log(p, 2, "VM disk starts at " + (target / VmSpecs.GB)
                    + " GB and grows into free storage automatically");
        } catch (Exception e) {
            log(p, 3, "Disk sizing skipped: " + e.getMessage());
        }
    }

    /**
     * Installs only the rootfs disk into a named VM slot — used to provision a NEW VM from the
     * Stryker template. The shared artifacts (qemu/kernel/initrd/libslirp) are assumed already
     * installed; this just populates {@code vms/<id>/rootfs.img}.
     */
    public static boolean installRootfsForVm(Context context, String vmId, Progress p) {
        File vmDir = RootlessPaths.vmDir(context, vmId);
        try {
            stage(p, Stage.PREPARING);
            if (!vmDir.exists() && !vmDir.mkdirs()) {
                log(p, 3, "Cannot create " + vmDir.getAbsolutePath());
                return false;
            }
            File rootfs = RootlessPaths.rootfs(context, vmId);

            stage(p, Stage.DECOMPRESSING_ROOTFS);
            if (assetsPresent(context)) {
                AssetManager am = context.getAssets();
                String rootfsAsset = rootfsAssetName(context);
                if (rootfsAsset == null) {
                    log(p, 3, "rootfs asset not found in assets/rootless");
                    return false;
                }
                if (isCompressed(rootfsAsset)) {
                    log(p, 1, "Decompressing " + rootfsAsset + " (this can take a minute)");
                    gunzipAsset(am, ASSET_DIR + "/" + rootfsAsset, rootfs, p);
                } else {
                    log(p, 1, "Copying " + rootfsAsset + " (already decompressed by the build)");
                    copyAsset(am, ASSET_DIR + "/" + rootfsAsset, rootfs, p, "rootfs.img");
                }
            } else {
                QemuDownloader.Bundle b = QemuDownloader.resolve(context);
                boolean compressed = b.rootfs != null && b.rootfs.url != null
                        && (b.rootfs.url.endsWith(".imgz") || b.rootfs.url.endsWith(".gz"));
                if (!compressed) {
                    if (!fetch(b.rootfs, rootfs, "rootfs.img", p)) return false;
                } else {
                    File archive = new File(vmDir, "rootfs.download");
                    if (!fetch(b.rootfs, archive, "rootfs", p)) return false;
                    log(p, 1, "Decompressing rootfs (this can take a minute)");
                    if (!gunzipFile(archive, rootfs, p)) {
                        //noinspection ResultOfMethodCallIgnored
                        archive.delete();
                        return false;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                }
            }

            stage(p, Stage.FINALIZING);
            ensureMinimumDiskFor(context, vmId, p);

            boolean ok = rootfs.exists() && rootfs.length() > 0;
            if (ok) {
                stage(p, Stage.DONE);
                log(p, 2, "VM disk ready");
            } else {
                log(p, 3, "Post-install verification failed for " + vmId);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "vm rootfs install failed", e);
            log(p, 3, "Install error: " + e.getMessage());
            return false;
        }
    }

    private static void ensureMinimumDiskFor(Context context, String vmId, Progress p) {
        try {
            File img = RootlessPaths.rootfs(context, vmId);
            if (!img.exists()) return;
            long target = Math.min((long) VmSpecs.MIN_DISK_GB * VmSpecs.GB,
                    VmSpecs.autoDiskTargetBytes(context));
            if (img.length() >= target) return;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
                raf.setLength(target);
                raf.getFD().sync();
            }
            if (img.length() < target) {
                log(p, 3, "Could not reserve " + (target / VmSpecs.GB) + " GB for the VM disk");
                return;
            }
            com.zalexdev.stryker.utils.Core core = new com.zalexdev.stryker.utils.Core(context);
            core.putBoolean(VmSpecs.K_RESIZE_PENDING, true);
            log(p, 2, "VM disk starts at " + (target / VmSpecs.GB)
                    + " GB and grows into free storage automatically");
        } catch (Exception e) {
            log(p, 3, "Disk sizing skipped: " + e.getMessage());
        }
    }

    private static final String HELPER_ASSET = "helper_server.sh";
    private static final String HELPER_MARKER = "/HELPER/.version";

    /**
     * Applies the helper role to a VM: boots it (if needed), stages helper_server.sh into its
     * 9p share, runs it in the guest (headless + server tooling, keeping the Realtek driver),
     * and polls the marker until it completes. Blocking — call off the main thread.
     */
    public static boolean provisionHelper(Context context, String vmId, Progress p) {
        try {
            com.zalexdev.stryker.utils.Core core = new com.zalexdev.stryker.utils.Core(context);
            RootlessEngine engine = core.vm(vmId);
            stage(p, Stage.PREPARING);

            log(p, 1, "Booting the helper VM…");
            if (!engine.isReady() && !engine.startBlocking(null)) {
                log(p, 3, "Helper VM failed to boot");
                return false;
            }

            java.io.File share = engine.resolveShareDir();
            if (share == null) {
                log(p, 3, "No share dir available to stage the helper script");
                return false;
            }
            java.io.File staged = new java.io.File(share, ".stryker-helper.sh");
            try (java.io.InputStream in = context.getAssets().open(HELPER_ASSET);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(staged)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
                out.getFD().sync();
            }

            stage(p, Stage.FINALIZING);
            log(p, 1, "Running the helper role setup (apt-get installs take a few minutes)…");
            engine.exec("cp -f /sdcard/Stryker/.stryker-helper.sh /tmp/.stryker-helper.sh; "
                    + "sed -i 's/\\r$//' /tmp/.stryker-helper.sh; "
                    + "chmod 0755 /tmp/.stryker-helper.sh; "
                    + "nohup bash /tmp/.stryker-helper.sh > /tmp/.stryker-helper.log 2>&1 & "
                    + "echo started");

            long deadline = System.currentTimeMillis() + 10 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                if (helperMarkerPresent(engine)) {
                    stage(p, Stage.DONE);
                    log(p, 2, "Helper role applied");
                    return true;
                }
                Thread.sleep(5000);
            }
            log(p, 3, "Helper role did not finish in time — check /tmp/.stryker-helper.log");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "helper provisioning failed", e);
            log(p, 3, "Helper provisioning error: " + e.getMessage());
            return false;
        }
    }

    private static boolean helperMarkerPresent(RootlessEngine engine) {
        for (String l : engine.exec("cat " + HELPER_MARKER + " 2>/dev/null")) {
            if (l != null && l.trim().equals("1")) return true;
        }
        return false;
    }

    private static void copyAsset(AssetManager am, String assetPath, File dest, Progress p, String label)
            throws IOException {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        long total = 0;
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int r;
            long lastReport = 0;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
                if (total - lastReport > 4 * 1024 * 1024) {
                    lastReport = total;
                    if (p != null) p.onBytes(label, total);
                }
            }
            out.flush();
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("rename failed for " + dest);
        }
        if (p != null) p.onBytes(label, total);
        log(p, 2, label + " extracted (" + mb(total) + ")");
    }

    private static void gunzipAsset(AssetManager am, String assetPath, File dest, Progress p)
            throws IOException {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        long total = 0;
        try (GZIPInputStream in = new GZIPInputStream(am.open(assetPath), 1 << 16);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int r;
            long lastReport = 0;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
                if (total - lastReport > 16 * 1024 * 1024) {
                    lastReport = total;
                    if (p != null) p.onBytes("rootfs.img", total);
                }
            }
            out.flush();
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("rename failed for " + dest);
        }
        log(p, 2, "rootfs.img ready (" + mb(total) + ")");
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void stage(Progress p, Stage s) { if (p != null) p.onStage(s); }
    private static void log(Progress p, int level, String msg) { if (p != null) p.onLog(level, msg); }
}
