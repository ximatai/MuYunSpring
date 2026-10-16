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
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class WebRequestContextTest {
    @Test
    void carriesLanguageExplicitlyAndRestoresTheWorkerLanguageOnSuccessAndFailure() throws Exception {
        WebRequestContext english;
        WebRequestContext chinese;
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("user", "User"))) {
            try (var language = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("en")) {
                english = WebRequestContext.capture().orElseThrow();
            }
            try (var language = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("zh-CN")) {
                chinese = WebRequestContext.capture().orElseThrow();
            }
        }
        try (var worker = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("fr")) {
            english.call(() -> {
                assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isEqualTo("en");
                chinese.run(() -> assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isEqualTo("zh-CN"));
                assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isEqualTo("en");
                return null;
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> english.run(() -> { throw new IllegalStateException("worker failure"); }))
                    .hasMessage("worker failure");
            assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isEqualTo("fr");
        }
        assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isNull();
    }

    @Test
    void concurrentCapturedLanguagesUsersAndTenantsRemainIndependentAndReleaseReusedWorkers() throws Exception {
        var contexts = new java.util.ArrayList<WebRequestContext>();
        var tags = java.util.List.of("zh-CN", "en", "fr");
        for (int lane = 0; lane < tags.size(); lane++) {
            try (var user = CurrentUserContext.use(CurrentUser.tenantUser("user-" + lane, "原名", "tenant-" + lane));
                 var tenant = TenantContext.use("tenant-" + lane);
                 var language = net.ximatai.muyun.spring.common.i18n.LanguageContext.use(tags.get(lane))) {
                contexts.add(WebRequestContext.capture().orElseThrow());
            }
        }
        var barrier = new java.util.concurrent.CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var results = new java.util.ArrayList<Future<Void>>();
            for (int lane = 0; lane < contexts.size(); lane++) {
                final int index = lane;
                results.add(executor.submit(() -> {
                    contexts.get(index).run(() -> {
                        try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                        assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("user-" + index);
                        assertThat(TenantContext.currentTenantId()).contains("tenant-" + index);
                        assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isEqualTo(tags.get(index));
                        assertThat(ActionExecutionContextHolder.current()).isEmpty();
                    });
                    assertThat(CurrentUserContext.currentUser()).isEmpty();
                    assertThat(TenantContext.currentTenantId()).isEmpty();
                    assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isNull();
                    return null;
                }));
            }
            for (var result : results) result.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(executor.submit(() -> net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).get()).isNull();
        }
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
        RequestTraceContext.clear();
        ActionExecutionContextHolder.clear();
        MDC.clear();
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
                    MDC.put("traceId", "mdc-worker");
                    WorkerObservation propagated = context.call(() -> new WorkerObservation(
                            CurrentUserContext.currentUser().orElseThrow().userId(),
                            TenantContext.currentTenantId().orElseThrow(),
                            RequestTraceContext.currentTraceId().orElseThrow(),
                            MDC.get("traceId"),
                            ActionExecutionContextHolder.current().isPresent()));
                    WorkerObservation restored = new WorkerObservation(
                            CurrentUserContext.currentUser().orElseThrow().userId(),
                            TenantContext.currentTenantId().orElseThrow(),
                            RequestTraceContext.currentTraceId().orElseThrow(),
                            MDC.get("traceId"),
                            ActionExecutionContextHolder.current().isPresent());
                    return new WorkerObservationPair(propagated, restored);
                }
            });

            WorkerObservationPair observations = result.get();
            assertThat(observations.propagated()).isEqualTo(
                    new WorkerObservation("request-user", "tenant-a", "trace-request", "trace-request", false));
            assertThat(observations.restored()).isEqualTo(
                    new WorkerObservation("worker-user", "tenant-worker", "trace-worker", "mdc-worker", false));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCarrySelectedTenantWithoutChangingSystemIdentityAndRestoreAfterFailure() {
        WebRequestContext snapshot;
        CurrentUser admin = CurrentUser.systemUser("admin", "Admin");
        try (var user = CurrentUserContext.use(admin);
             var tenant = TenantContext.use("selected-tenant")) {
            snapshot = WebRequestContext.capture().orElseThrow();
        }
        try (var tenant = TenantContext.use("worker-tenant")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.call(() -> {
                assertThat(CurrentUserContext.currentUser()).contains(admin);
                assertThat(TenantContext.currentTenantId()).contains("selected-tenant");
                assertThat(TenantContext.isSystem()).isFalse();
                throw new IllegalStateException("worker failure");
            })).hasMessage("worker failure");
            assertThat(TenantContext.currentTenantId()).contains("worker-tenant");
            assertThat(CurrentUserContext.currentUser()).isEmpty();
        }
    }

    @Test
    void shouldRetainAuthenticatedTenantWhenCapturedWithoutAnExplicitScope() throws Exception {
        WebRequestContext snapshot;
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("u", "U", "tenant-a"))) {
            snapshot = WebRequestContext.capture().orElseThrow();
        }
        assertThat(snapshot.call(() -> TenantContext.currentTenantId().orElseThrow())).isEqualTo("tenant-a");
        assertThat(TenantContext.hasContext()).isFalse();
    }

    @Test
    void shouldNotCaptureAnonymousOrActionAuthorizationContext() {
        assertThat(WebRequestContext.capture()).isEmpty();
    }

    private record WorkerObservation(String userId, String tenantId, String traceId, String mdcTraceId,
                                     boolean hasActionContext) {
    }

    private record WorkerObservationPair(WorkerObservation propagated, WorkerObservation restored) {
    }
}
