package com.zalexdev.stryker.module;

/**
 * A frozen, reusable VM image: a named disk snapshot plus launch defaults.
 *
 * <p>Templates live in the template library; a VM is instantiated from one via a sparse clone
 * (see {@code VmRegistry.createFromTemplate}). Templates are the unit that turns "purpose-built
 * solutions" (metasploit box, juice-shop server, pi-hole, evil-AP, …) into reusable, spin-up-able
 * artifacts instead of a single baked-in image.</p>
 */
public final class VmTemplate {

    public final String name;
    public final String description;
    /** Absolute path to the frozen rootfs image on device. */
    public final String rootfsPath;
    public final int defaultCpus;
    public final int defaultRamMb;

    public VmTemplate(String name, String description, String rootfsPath,
                      int defaultCpus, int defaultRamMb) {
        this.name = name;
        this.description = description;
        this.rootfsPath = rootfsPath;
        this.defaultCpus = defaultCpus;
        this.defaultRamMb = defaultRamMb;
    }
}
