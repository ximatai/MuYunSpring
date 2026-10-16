package net.ximatai.muyun.spring.common.identity;

import java.util.Objects;
import java.util.Optional;

public final class ActingContextHolder {
    private static final ThreadLocal<ActingContext> CURRENT = new ThreadLocal<>();

    private ActingContextHolder() {
    }

    public static Optional<ActingContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope use(ActingContext context) {
        ActingContext previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context must not be null"));
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Resolves a delegation as its actual operator, without inheriting an ambient principal. */
    public static Scope without() {
        ActingContext previous = CURRENT.get();
        CURRENT.remove();
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final ActingContext previous;

        private Scope(ActingContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
                return;
            }
            CURRENT.set(previous);
        }
    }
}
