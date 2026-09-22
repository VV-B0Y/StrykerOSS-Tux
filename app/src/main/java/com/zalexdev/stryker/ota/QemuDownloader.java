package com.zalexdev.stryker.ota;

import android.content.Context;

public final class QemuDownloader {

    private QemuDownloader() {}

    public static final class Bundle {
        public final RemoteManifest.Asset qemu;
        public final RemoteManifest.Asset kernel;
        public final RemoteManifest.Asset initrd;
        public final RemoteManifest.Asset libslirp;
        public final RemoteManifest.Asset rootfs;

        Bundle(RemoteManifest.Asset qemu, RemoteManifest.Asset kernel, RemoteManifest.Asset initrd,
               RemoteManifest.Asset libslirp, RemoteManifest.Asset rootfs) {
            this.qemu = qemu;
            this.kernel = kernel;
            this.initrd = initrd;
            this.libslirp = libslirp;
            this.rootfs = rootfs;
        }
    }

    public static Bundle resolve(Context context) {
        if (useTest(context)) return testBundle();
        return mainBundle();
    }

    /** The stable channel: every artifact from rootless-main. */
    private static Bundle mainBundle() {
        return new Bundle(
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_QEMU, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_KERNEL, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_INITRD, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_LIBSLIRP, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_ROOTFS, "", 0));
    }

    public static final String PREF_USE_TEST = "rootless_use_test";

    public static boolean useTest(Context context) {
        return context.getSharedPreferences(StrykerEndpoints.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_USE_TEST, false);
    }

    public static void setUseTest(Context context, boolean value) {
        context.getSharedPreferences(StrykerEndpoints.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_USE_TEST, value).apply();
    }

    /** The rootless-650 test channel: kernel/initrd/rootfs from rootless-650, qemu+libslirp from main. */
    private static Bundle testBundle() {
        return new Bundle(
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_QEMU, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_KERNEL, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_INITRD, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_LIBSLIRP, "", 0),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_ROOTFS, "", 0));
    }
}
