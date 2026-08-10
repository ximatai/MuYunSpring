package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Explicit, short-lived request context snapshot for work dispatched from an authenticated Web request.
 *
 * <p>This type deliberately carries only the authenticated user, the corresponding tenant scope, and
 * the request trace. It does not carry action authorization or acting-delegation scopes: background
 * work must not silently extend a request's authorization decision or delegation relationship.</p>
 */
public final class WebRequestContext {
    private final CurrentUser currentUser;
    private final String traceId;

    private WebRequestContext(CurrentUser currentUser, String traceId) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.traceId = traceId;
    }

    /** Captures the authenticated request identity when one is currently bound. */
    public static Optional<WebRequestContext> capture() {
        return CurrentUserContext.currentUser()
                .map(currentUser -> new WebRequestContext(currentUser,
                        RequestTraceContext.currentTraceId().orElse(null)));
    }

    /** Wraps one task so it executes with this request's identity, tenant, and trace scopes. */
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        return () -> run(task);
    }

    /** Wraps one task so it executes with this request's identity, tenant, and trace scopes. */
    public <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        return () -> call(task);
    }

    public void run(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            call(() -> {
                task.run();
                return null;
            });
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("request context task failed", exception);
        }
    }

    public <T> T call(Callable<T> task) throws Exception {
        Objects.requireNonNull(task, "task must not be null");
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(currentUser);
             TenantContext.Scope ignoredTenant = tenantScope();
             RequestTraceContext.Scope ignoredTrace = traceScope()) {
            return task.call();
        }
    }

    private TenantContext.Scope tenantScope() {
        if (currentUser.system()) {
            return TenantContext.system("authenticated web request async work");
        }
        return TenantContext.use(currentUser.tenantId());
    }

    private RequestTraceContext.Scope traceScope() {
        return traceId == null ? () -> { } : RequestTraceContext.use(traceId);
    }
}
