package com.zalexdev.stryker.engine;

import android.content.Context;

import java.io.File;

public final class RootlessPaths {

    private RootlessPaths() {}

    public static File base(Context c) {
        return new File(c.getFilesDir(), "rootless");
    }

    public static File qemuBin(Context c)   { return new File(base(c), "qemu-system-aarch64"); }
    public static File libslirp(Context c)  { return new File(base(c), "libslirp.so"); }
    public static File kernel(Context c)    { return new File(base(c), "Image"); }
    public static File initrd(Context c)    { return new File(base(c), "initrd.img"); }
    public static File rootfs(Context c)    { return new File(base(c), "rootfs.img"); }
    public static File rootfsGz(Context c)  { return new File(base(c), "rootfs.img.gz"); }

    public static File qmpSock(Context c)   { return new File(base(c), "qmp.sock"); }
    public static File serialSock(Context c){ return new File(base(c), "serial.sock"); }
    public static File serialLog(Context c){ return new File(base(c), "serial.log"); }
    public static File termSock(Context c)  { return new File(base(c), "term.sock"); }
    public static File bootLog(Context c)   { return new File(base(c), "boot.log"); }

    public static final int GUEST_EXEC_PORT = 1050;
    public static final int HOST_EXEC_PORT  = 1050;
    public static final String HOST_LOOPBACK = "127.0.0.1";

    public static final int GUEST_TERM_PORT = 1051;
    public static final int HOST_TERM_PORT  = 1051;

    public static final int GUEST_PTY_PORT = 1052;
    public static final int HOST_PTY_PORT  = 1052;

    public static final int GUEST_SSH_PORT = 22;
    public static final int HOST_SSH_PORT  = 2222;

    public static final int GUEST_CAPTURE_PORT = 1053;
    public static final int HOST_CAPTURE_PORT  = 1053;

    public static File activeFlag(Context c) {
        return new File(base(c), ".active");
    }
    public static final String ACTIVE_FLAG_PATH =
            "/data/data/com.zalexdev.stryker/files/rootless/.active";
}
