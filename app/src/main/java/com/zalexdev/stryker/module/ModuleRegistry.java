package com.zalexdev.stryker.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single point where features register themselves.
 *
 * <p>The shell builds the drawer / navigation / template browser by reading {@link #all()} —
 * it never imports a feature directly. During the future Gradle split, each {@code :feature:*}
 * module calls {@link #register(StrykerModule)} from a small entry point; the core stays
 * untouched when a feature is added or removed.</p>
 */
public final class ModuleRegistry {

    private static final List<StrykerModule> MODULES = new ArrayList<>();

    private ModuleRegistry() {}

    /** Idempotent registration (ignores nulls and duplicate ids). */
    public static synchronized void register(StrykerModule m) {
        if (m == null) return;
        for (StrykerModule e : MODULES) {
            if (e.id().equals(m.id())) return;
        }
        MODULES.add(m);
    }

    /** Snapshot of all registered modules, in registration order. */
    public static synchronized List<StrykerModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(MODULES));
    }

    /** The module with the given id, or {@code null}. */
    public static synchronized StrykerModule byId(String id) {
        for (StrykerModule m : MODULES) {
            if (m.id().equals(id)) return m;
        }
        return null;
    }

    /** All modules that ship a VM/container template. */
    public static synchronized List<StrykerModule> withTemplates() {
        List<StrykerModule> out = new ArrayList<>();
        for (StrykerModule m : MODULES) {
            if (m.template() != null) out.add(m);
        }
        return out;
    }
}
