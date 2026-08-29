package net.ximatai.muyun.spring.platform.metadata;

import java.util.function.Supplier;

/** Coalesces the schema/runtime side effects of one governed capability mutation. */
final class MetadataCapabilityGovernanceMutationContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MetadataCapabilityGovernanceMutationContext() {
    }

    static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    static <T> T run(Supplier<T> action) {
        boolean previous = isActive();
        ACTIVE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous) ACTIVE.set(Boolean.TRUE); else ACTIVE.remove();
        }
    }
}
