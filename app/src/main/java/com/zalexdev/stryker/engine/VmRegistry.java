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
        if (vms.remove(id) != null) {
            engines.remove(id);
            save();
        }
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
