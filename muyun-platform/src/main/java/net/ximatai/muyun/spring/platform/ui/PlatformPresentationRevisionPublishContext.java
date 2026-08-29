package net.ximatai.muyun.spring.platform.ui;

/** Internal guard reserved for the future presentation revision publish transaction. */
final class PlatformPresentationRevisionPublishContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PlatformPresentationRevisionPublishContext() {
    }

    static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    static Scope open() {
        boolean previous = active();
        ACTIVE.set(Boolean.TRUE);
        return () -> {
            if (previous) {
                ACTIVE.set(Boolean.TRUE);
            } else {
                ACTIVE.remove();
            }
        };
    }

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
