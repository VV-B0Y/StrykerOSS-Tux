package com.zalexdev.stryker.module;

import androidx.fragment.app.Fragment;

/**
 * A pluggable feature.
 *
 * <p>The shell (drawer, navigation, template browser) reads {@link ModuleRegistry} and renders
 * whatever is registered, so the core has zero compile-time coupling to any individual feature.
 * This interface is deliberately kept dependency-light (androidx Fragment + {@link VmTemplate})
 * so it can move verbatim into a {@code :module-api} Gradle module during the future split.</p>
 */
public interface StrykerModule {

    /** Stable unique id (e.g. {@code "metasploit"}, {@code "pihole"}). */
    String id();

    /** Human title shown in the drawer / template browser. */
    String title();

    /** One-line description. */
    String description();

    /** The fragment this module opens (its main screen). */
    Class<? extends Fragment> fragment();

    /** A VM/container template this module ships, or {@code null} for UI-only modules. */
    VmTemplate template();
}
