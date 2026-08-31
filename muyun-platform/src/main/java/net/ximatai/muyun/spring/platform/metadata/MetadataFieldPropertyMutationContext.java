package net.ximatai.muyun.spring.platform.metadata;

import java.util.function.Supplier;

/** Suppresses per-config runtime refresh while a change-set publishes atomically. */
final class MetadataFieldPropertyMutationContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MetadataFieldPropertyMutationContext() {
    }

    static <T> T run(Supplier<T> action) {
        boolean previous = Boolean.TRUE.equals(ACTIVE.get());
        ACTIVE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous) ACTIVE.set(Boolean.TRUE);
            else ACTIVE.remove();
        }
    }

    static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
