package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class WebRequestContextTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
        RequestTraceContext.clear();
        ActionExecutionContextHolder.clear();
    }

    @Test
    void shouldExplicitlyRestoreUserTenantAndTraceForWorkerTaskWithoutLeakingThem() throws Exception {
        WebRequestContext context;
        CurrentUser requestUser = CurrentUser.tenantUser("request-user", "Alice", "tenant-a");
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(requestUser);
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a");
             RequestTraceContext.Scope ignoredTrace = RequestTraceContext.use("trace-request");
             ActionExecutionContextHolder.Scope ignoredAction = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPlatformAction("demo.agent", PlatformAction.QUERY,
                             java.util.Set.of(), java.util.Optional.of(requestUser)))) {
            context = WebRequestContext.capture().orElseThrow();
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkerObservationPair> result = executor.submit(() -> {
                try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                        CurrentUser.tenantUser("worker-user", "Worker", "tenant-worker"));
                     TenantContext.Scope ignoredTenant = TenantContext.use("tenant-worker");
                     RequestTraceContext.Scope ignoredTrace = RequestTraceContext.use("trace-worker")) {
                    WorkerObservation propagated = context.call(() -> new WorkerObservation(
                            CurrentUserContext.currentUser().orElseThrow().userId(),
                            TenantContext.currentTenantId().orElseThrow(),
                            RequestTraceContext.currentTraceId().orElseThrow(),
                            ActionExecutionContextHolder.current().isPresent()));
                    WorkerObservation restored = new WorkerObservation(
                            CurrentUserContext.currentUser().orElseThrow().userId(),
                            TenantContext.currentTenantId().orElseThrow(),
                            RequestTraceContext.currentTraceId().orElseThrow(),
                            ActionExecutionContextHolder.current().isPresent());
                    return new WorkerObservationPair(propagated, restored);
                }
            });

            WorkerObservationPair observations = result.get();
            assertThat(observations.propagated()).isEqualTo(
                    new WorkerObservation("request-user", "tenant-a", "trace-request", false));
            assertThat(observations.restored()).isEqualTo(
                    new WorkerObservation("worker-user", "tenant-worker", "trace-worker", false));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotCaptureAnonymousOrActionAuthorizationContext() {
        assertThat(WebRequestContext.capture()).isEmpty();
    }

    private record WorkerObservation(String userId, String tenantId, String traceId, boolean hasActionContext) {
    }

    private record WorkerObservationPair(WorkerObservation propagated, WorkerObservation restored) {
    }
}
