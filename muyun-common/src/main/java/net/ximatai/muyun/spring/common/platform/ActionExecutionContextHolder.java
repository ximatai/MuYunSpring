package net.ximatai.muyun.spring.common.platform;

import java.util.Objects;
import java.util.Optional;

public final class ActionExecutionContextHolder {
    private static final ThreadLocal<ActionExecutionContext> CURRENT = new ThreadLocal<>();

    private ActionExecutionContextHolder() {
    }

    public static Optional<ActionExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope use(ActionExecutionContext context) {
        ActionExecutionContext previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context must not be null"));
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs control-plane work without carrying a caller's business action into another execution. */
    public static Scope without() {
        ActionExecutionContext previous = CURRENT.get();
        CURRENT.remove();
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final ActionExecutionContext previous;

        private Scope(ActionExecutionContext previous) {
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
