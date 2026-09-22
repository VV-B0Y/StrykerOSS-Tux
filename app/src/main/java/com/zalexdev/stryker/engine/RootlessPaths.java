package com.zalexdev.stryker.engine;

import android.content.Context;

import java.io.File;

/**
 * Filesystem + port layout for the rootless VM backend.
 *
 * <p>Shared artifacts (one copy for every VM) live directly under {@code files/rootless/}:
 * the QEMU binary, libslirp, and a {@code boot/} dir holding the default kernel + initrd.
 * Everything that distinguishes one VM from another lives under {@code vms/<id>/}: its
 * rootfs.img, an optional per-VM kernel/initrd override, its sockets/logs, and its {@code .active}
 * flag. Host port numbers are derived from the VM's index ({@code offset = index * 1000}) so two
 * VMs can run side by side without port collisions.
 */
public final class RootlessPaths {

    private RootlessPaths() {}

    // ---- shared artifacts (one copy for all VMs) ----

    public static File base(Context c)        { return new File(c.getFilesDir(), "rootless"); }
    public static File qemuBin(Context c)     { return new File(base(c), "qemu-system-aarch64"); }
    public static File libslirp(Context c)    { return new File(base(c), "libslirp.so"); }
    public static File sharedBoot(Context c)  { return new File(base(c), "boot"); }
    public static File sharedKernel(Context c){ return new File(sharedBoot(c), "Image"); }
    public static File sharedInitrd(Context c){ return new File(sharedBoot(c), "initrd.img"); }
    public static File vmsDir(Context c)      { return new File(base(c), "vms"); }
    public static File registryFile(Context c){ return new File(vmsDir(c), "registry.json"); }
    /** Which VM the UI is currently focused on ("vm0"/"vm1"); read by the terminal module too. */
    public static File selectedFile(Context c){ return new File(base(c), ".selected"); }

    // ---- per-VM ----

    public static File vmDir(Context c, String id) { return new File(vmsDir(c), id); }

    public static File rootfs(Context c, String id)        { return new File(vmDir(c, id), "rootfs.img"); }
    public static File rootfsGz(Context c, String id)      { return new File(vmDir(c, id), "rootfs.img.gz"); }
    public static File kernelOverride(Context c, String id){ return new File(vmDir(c, id), "Image"); }
    public static File initrdOverride(Context c, String id){ return new File(vmDir(c, id), "initrd.img"); }

    /** Effective kernel: per-VM override if present, else the shared default. */
    public static File kernel(Context c, String id) {
        File ov = kernelOverride(c, id);
        return ov.exists() ? ov : sharedKernel(c);
    }

    /** Effective initrd: per-VM override if present, else the shared default. */
    public static File initrd(Context c, String id) {
        File ov = initrdOverride(c, id);
        return ov.exists() ? ov : sharedInitrd(c);
    }

    public static File qmpSock(Context c, String id)    { return new File(vmDir(c, id), "qmp.sock"); }
    public static File serialSock(Context c, String id) { return new File(vmDir(c, id), "serial.sock"); }
    public static File serialLog(Context c, String id)  { return new File(vmDir(c, id), "serial.log"); }
    public static File termSock(Context c, String id)   { return new File(vmDir(c, id), "term.sock"); }
    public static File bootLog(Context c, String id)    { return new File(vmDir(c, id), "boot.log"); }
    public static File activeFlag(Context c, String id) { return new File(vmDir(c, id), ".active"); }

    // ---- guest ports (constant inside every VM) ----

    public static final int GUEST_EXEC_PORT    = 1050;
    public static final int GUEST_TERM_PORT    = 1051;
    public static final int GUEST_PTY_PORT     = 1052;
    public static final int GUEST_CAPTURE_PORT = 1053;
    public static final int GUEST_SSH_PORT     = 22;

    // ---- host ports (per-VM; offset = index * 1000) ----

    public static final int PORT_OFFSET = 1000;
    public static int hostExecPort(int index)    { return GUEST_EXEC_PORT    + index * PORT_OFFSET; }
    public static int hostTermPort(int index)    { return GUEST_TERM_PORT    + index * PORT_OFFSET; }
    public static int hostPtyPort(int index)     { return GUEST_PTY_PORT     + index * PORT_OFFSET; }
    public static int hostCapturePort(int index) { return GUEST_CAPTURE_PORT + index * PORT_OFFSET; }
    public static int hostSshPort(int index)     { return 2222 + index; }

    public static final String HOST_LOOPBACK = "127.0.0.1";

    // ---- legacy vm0 aliases (kept so existing single-VM call sites compile unchanged) ----

    public static final int HOST_EXEC_PORT    = GUEST_EXEC_PORT;
    public static final int HOST_TERM_PORT    = GUEST_TERM_PORT;
    public static final int HOST_PTY_PORT     = GUEST_PTY_PORT;
    public static final int HOST_CAPTURE_PORT = GUEST_CAPTURE_PORT;
    public static final int HOST_SSH_PORT     = 2222;

    public static File rootfs(Context c)  { return rootfs(c, "vm0"); }
    public static File rootfsGz(Context c){ return rootfsGz(c, "vm0"); }
    public static File kernel(Context c)  { return kernel(c, "vm0"); }
    public static File initrd(Context c)  { return initrd(c, "vm0"); }
    public static File qmpSock(Context c)     { return qmpSock(c, "vm0"); }
    public static File serialSock(Context c)  { return serialSock(c, "vm0"); }
    public static File serialLog(Context c)   { return serialLog(c, "vm0"); }
    public static File termSock(Context c)    { return termSock(c, "vm0"); }
    public static File bootLog(Context c)     { return bootLog(c, "vm0"); }
    public static File activeFlag(Context c)  { return activeFlag(c, "vm0"); }

    public static final String ACTIVE_FLAG_PATH =
            "/data/data/com.zalexdev.stryker/files/rootless/vms/vm0/.active";
}
