package net.ximatai.muyun.spring.common.identity;

import java.util.Objects;
import java.util.Optional;

public final class CurrentUserContext {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static Optional<CurrentUser> currentUser() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<String> currentTenantId() {
        return currentUser().map(CurrentUser::tenantId);
    }

    public static boolean isSystem() {
        return currentUser().map(CurrentUser::system).orElse(false);
    }

    public static Scope use(CurrentUser currentUser) {
        CurrentUser previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(currentUser, "currentUser must not be null"));
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Isolates trusted resolution/control work without substituting another actor. */
    public static Scope without() {
        CurrentUser previous = CURRENT.get();
        CURRENT.remove();
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final CurrentUser previous;

        private Scope(CurrentUser previous) {
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
