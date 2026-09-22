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
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_QEMU,
                        "2a87f531371b3f8d45141d48632e0dbe2c3d968fbcb385ba105d671479fb8c99", 43800304L),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_KERNEL,
                        "cbe59a02e7ea979a150661032440c94e2c4db0b735af2416e11ae5cac15a58e4", 37605312L),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_INITRD,
                        "655f3ef013e7818e9ee874cf3b44a4c0bdc8a586c986cf237cb74c41862dfd02", 38301815L),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_LIBSLIRP,
                        "226372426fda32c9fccd8e831d0901a86bfff3c3e6f7a60336d6dde149f756c4", 1145496L),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_ROOTFS,
                        "f80c2b1e2433c3036aa745da2a5935cf5dd61b65b17bbeabae87b49bd68a12ef", 427974567L));
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
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_QEMU,
                        "2a87f531371b3f8d45141d48632e0dbe2c3d968fbcb385ba105d671479fb8c99", 43800304L),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_KERNEL,
                        "80b5fd85c1280e11fcaee721bbad1781ebc91b0370ea49da6729fcecc8649d24", 28676104L),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_INITRD,
                        "30cc3d91d673de371946764678d534eab2e80669e393009915a3785e6e4f4017", 16308339L),
                new RemoteManifest.Asset(StrykerEndpoints.FALLBACK_ROOTLESS_LIBSLIRP,
                        "226372426fda32c9fccd8e831d0901a86bfff3c3e6f7a60336d6dde149f756c4", 1145496L),
                new RemoteManifest.Asset(StrykerEndpoints.TEST_ROOTLESS_ROOTFS,
                        "dfa844f8b9cebe121b60ecdb4da9c41ffd701430b2c9d0f6bdc90c66d2c26c82", 321664844L));
    }
}
