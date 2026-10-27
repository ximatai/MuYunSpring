package net.ximatai.muyun.spring.common.identity;

import java.util.Optional;

/** Request-scoped authentication provenance, captured into events before asynchronous delivery. */
public final class RequestAuthenticationContext {
    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();
    private RequestAuthenticationContext() {}
    public static Optional<AuthenticatedUser> current() { return Optional.ofNullable(CURRENT.get()); }
    public static Scope use(AuthenticatedUser authentication) {
        var previous = CURRENT.get();
        CURRENT.set(java.util.Objects.requireNonNull(authentication, "authentication"));
        return new Scope(previous);
    }
    public static final class Scope implements AutoCloseable {
        private final AuthenticatedUser previous;
        private Scope(AuthenticatedUser previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
