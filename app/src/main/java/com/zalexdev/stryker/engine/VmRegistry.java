package com.zalexdev.stryker.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the app's VMs (up to {@link #MAX_VMS}) and the factory for their engines.
 *
 * <p>Each VM has a stable id ({@code vm0}, {@code vm1}), a 0-based index (which drives its host
 * port range), and a free-text display name. The registry is persisted as JSON under
 * {@code files/rootless/vms/registry.json}. On first access it migrates the legacy flat layout
 * ({@code rootless/rootfs.img}, {@code rootless/Image}, {@code rootless/initrd.img}) into the new
 * per-VM layout ({@code vms/vm0/rootfs.img} and {@code boot/} for the shared kernel/initrd), so an
 * existing installed guest is preserved as {@code vm0}.
 */
public final class VmRegistry {

    private static final String TAG = "VmRegistry";

    public static final int MAX_VMS = 2;
    public static final String DEFAULT_ID = "vm0";

    private static volatile VmRegistry instance;

    private final Context app;
    private final Map<String, VmInfo> vms = new LinkedHashMap<>();
    private final Map<String, RootlessEngine> engines = new LinkedHashMap<>();

    public static final class VmInfo {
        public final String id;
        public final int index;
        public volatile String name;

        VmInfo(String id, int index, String name) {
            this.id = id; this.index = index; this.name = name;
        }
    }

    private VmRegistry(Context context) {
        this.app = context.getApplicationContext();
        load();
    }

    public static VmRegistry get(Context context) {
        if (instance == null) {
            synchronized (VmRegistry.class) {
                if (instance == null) instance = new VmRegistry(context);
            }
        }
        return instance;
    }

    public synchronized List<VmInfo> list() {
        return new ArrayList<>(vms.values());
    }

    public synchronized VmInfo get(String id) {
        return vms.get(id);
    }

    public synchronized int count() {
        return vms.size();
    }

    public synchronized boolean atCapacity() {
        return vms.size() >= MAX_VMS;
    }

    /** Allocates the next free VM slot (id + index) with the given display name. */
    public synchronized VmInfo create(String name) throws IllegalStateException {
        if (atCapacity()) throw new IllegalStateException("VM limit of " + MAX_VMS + " reached");
        int index = 0;
        while (vms.containsKey("vm" + index)) index++;
        String id = "vm" + index;
        VmInfo info = new VmInfo(id, index, name == null || name.trim().isEmpty()
                ? ("VM " + (index + 1)) : name.trim());
        vms.put(id, info);
        save();
        return info;
    }

    public synchronized void rename(String id, String name) {
        VmInfo info = vms.get(id);
        if (info == null) return;
        info.name = (name == null || name.trim().isEmpty()) ? info.name : name.trim();
        save();
    }

    /** Unregisters a VM. Does NOT delete its disk — the caller owns disk cleanup. */
    public synchronized void remove(String id) {
        remove(id, false);
    }

    /** Unregisters a VM, optionally deleting its disk directory. */
    public synchronized void remove(String id, boolean deleteDisk) {
        if (vms.remove(id) != null) {
            engines.remove(id);
            if (deleteDisk) deleteRecursively(RootlessPaths.vmDir(app, id));
            save();
        }
    }

    /**
     * Clones an existing VM's disk into a new slot. The disk is copied sparse-aware (only
     * allocated extents are copied, so a 279 GB sparse image clones as its ~few GB of real
     * data), then optionally grown to {@code newSizeBytes} (grow-only; shrink is not supported
     * on a raw image). A per-VM kernel/initrd override on the source is copied too.
     */
    public synchronized VmInfo clone(String srcId, String name, long newSizeBytes)
            throws IllegalStateException, IOException {
        VmInfo src = vms.get(srcId);
        if (src == null) throw new IllegalArgumentException("source VM not found: " + srcId);
        if (atCapacity()) throw new IllegalStateException("VM limit of " + MAX_VMS + " reached");

        int index = nextFreeIndex();
        String id = "vm" + index;

        File dstDir = RootlessPaths.vmDir(app, id);
        if (!dstDir.exists() && !dstDir.mkdirs()) throw new IOException("cannot create " + dstDir);

        File srcImg = RootlessPaths.rootfs(app, srcId);
        File dstImg = RootlessPaths.rootfs(app, id);
        if (!srcImg.exists()) throw new IOException("source disk missing: " + srcImg);
        copySparse(srcImg, dstImg);

        if (newSizeBytes > dstImg.length()) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dstImg, "rw")) {
                raf.setLength(newSizeBytes);
            }
        }

        copyOverride(srcId, id);

        VmInfo info = new VmInfo(id, index, name == null || name.trim().isEmpty()
                ? ("VM " + (index + 1)) : name.trim());
        vms.put(id, info);
        save();
        return info;
    }

    private int nextFreeIndex() {
        int index = 0;
        while (vms.containsKey("vm" + index)) index++;
        return index;
    }

    private void copyOverride(String srcId, String dstId) {
        File sK = RootlessPaths.kernelOverride(app, srcId);
        File sI = RootlessPaths.initrdOverride(app, srcId);
        if (sK.exists()) copyFile(sK, RootlessPaths.kernelOverride(app, dstId));
        if (sI.exists()) copyFile(sI, RootlessPaths.initrdOverride(app, dstId));
    }

    /** Sparse-aware copy: copies only allocated extents, preserving holes (SEEK_DATA/SEEK_HOLE). */
    private static void copySparse(File src, File dst) throws IOException {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(src, "r");
             java.io.RandomAccessFile out = new java.io.RandomAccessFile(dst, "rw")) {
            long size = in.length();
            java.io.FileDescriptor fd = in.getFD();
            byte[] buf = new byte[1 << 20];
            long pos = 0;
            try {
                while (pos < size) {
                    long data = android.system.Os.lseek(fd, pos, 3); // SEEK_DATA
                    if (data < 0 || data >= size) break;
                    long hole = android.system.Os.lseek(fd, data, 4); // SEEK_HOLE
                    if (hole < 0 || hole > size) hole = size;
                    in.seek(data);
                    out.seek(data);
                    long remain = hole - data;
                    while (remain > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                    pos = hole;
                }
            } catch (android.system.ErrnoException e) {
                throw new IOException("sparse copy failed: " + e.getMessage(), e);
            }
            out.setLength(size);
        }
    }

    private static void copyFile(File src, File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } catch (IOException ignored) {}
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Returns (creating if needed) the engine for a VM. */
    public synchronized RootlessEngine engine(Context context, String id) {
        VmInfo info = vms.get(id);
        if (info == null) throw new IllegalArgumentException("unknown VM: " + id);
        RootlessEngine e = engines.get(id);
        if (e == null) {
            e = new RootlessEngine(context, id, info.index);
            engines.put(id, e);
        }
        return e;
    }

    private void load() {
        File reg = RootlessPaths.registryFile(app);
        if (reg.exists()) {
            try {
                String text = readAll(reg);
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id");
                    int index = o.optInt("index", -1);
                    String name = o.optString("name");
                    if (id.isEmpty() || index < 0 || vms.containsKey(id)) continue;
                    vms.put(id, new VmInfo(id, index, name));
                }
                if (!vms.isEmpty()) return;
            } catch (Throwable t) {
                Log.w(TAG, "could not read registry, rebuilding: " + t.getMessage());
                vms.clear();
            }
        }
        migrateLegacy();
        if (!vms.containsKey(DEFAULT_ID)) {
            vms.put(DEFAULT_ID, new VmInfo(DEFAULT_ID, 0, "VM 1"));
        }
        save();
    }

    /** Moves the legacy flat files under base/ into the new shared/per-VM layout. */
    private void migrateLegacy() {
        File base = RootlessPaths.base(app);
        File vm0 = RootlessPaths.vmDir(app, DEFAULT_ID);
        File boot = RootlessPaths.sharedBoot(app);
        // Ensure the full structure exists even on a fresh device (installer writes into it).
        if (!vm0.mkdirs() && !vm0.isDirectory()) Log.w(TAG, "could not create " + vm0);
        if (!boot.mkdirs() && !boot.isDirectory()) Log.w(TAG, "could not create " + boot);
        if (!base.isDirectory()) base.mkdirs();

        moveIfExists(new File(base, "rootfs.img"), new File(vm0, "rootfs.img"));
        moveIfExists(new File(base, "rootfs.img.gz"), new File(vm0, "rootfs.img.gz"));
        moveIfExists(new File(base, "Image"), new File(boot, "Image"));
        moveIfExists(new File(base, "initrd.img"), new File(boot, "initrd.img"));
        moveIfExists(new File(base, ".active"), new File(vm0, ".active"));
    }

    private static void moveIfExists(File src, File dst) {
        try {
            if (src.exists() && !dst.exists() && !src.renameTo(dst)) {
                Log.w(TAG, "could not move " + src + " -> " + dst);
            }
        } catch (Throwable ignored) {}
    }

    private static String readAll(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
        return out.toString("UTF-8");
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (VmInfo info : vms.values()) {
                JSONObject o = new JSONObject();
                o.put("id", info.id);
                o.put("index", info.index);
                o.put("name", info.name);
                arr.put(o);
            }
            File reg = RootlessPaths.registryFile(app);
            if (reg.getParentFile() != null) reg.getParentFile().mkdirs();
            try (FileWriter fw = new FileWriter(reg)) {
                fw.write(arr.toString());
                fw.flush();
            }
        } catch (Throwable t) {
            Log.w(TAG, "save failed: " + t.getMessage());
        }
    }
}
