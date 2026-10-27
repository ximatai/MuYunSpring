package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEventAttributionTest {
    private final CurrentUser actor = CurrentUser.systemUser("admin", "admin");

    @Test void capturesOnlyMatchingAuthorizationAndKeepsTheActualOperator() {
        for (String mismatch : List.of("none", "module", "action", "contextUser", "authorizationUser")) {
            var contextUser = mismatch.equals("contextUser") ? CurrentUser.systemUser("other", "other") : actor;
            var context = ActionExecutionContext.ofPlatformAction(
                    mismatch.equals("module") ? "other.records" : "test.records",
                    mismatch.equals("action") ? PlatformAction.UPDATE : PlatformAction.CREATE,
                    Set.of(), Optional.of(contextUser));
            var authorization = new ActionAuthorizationResult("ROLE_GRANTED",
                    mismatch.equals("authorizationUser") ? "other" : contextUser.userId(), "SYSTEM", "test.permission", "create");
            try (var user = CurrentUserContext.use(actor);
                 var action = ActionExecutionContextHolder.use(context.withAuthorizationResult(authorization))) {
                var event = event("create");
                assertThat(event.operatorId()).isEqualTo("admin");
                assertThat(event.operatorType()).isEqualTo("SYSTEM");
                assertThat(event.authorizationDecision()).isEqualTo(mismatch.equals("none") ? "ROLE_GRANTED" : null);
                assertThat(event.authorizationPermissionCode()).isEqualTo(mismatch.equals("none") ? "test.permission" : null);
            }
        }
    }

    @Test void delayedEventsRetainProducerAndExplicitHistoricalConstructionDoesNotCaptureReader() {
        RuntimeEvent captured;
        try (var user = CurrentUserContext.use(actor)) { captured = event(null); }
        try (var reader = CurrentUserContext.use(CurrentUser.systemUser("reader", "reader"))) {
            assertThat(captured.operatorId()).isEqualTo("admin");
            var historical = new RuntimeEvent(null, null, RuntimeEventType.AFTER_CREATE, "test.records", "record",
                    "r", null, null, false, RuntimeMutationSource.BUSINESS, Map.of(), null);
            assertThat(historical.operatorId()).isNull();
        }
        assertThat(event(null).operatorId()).isNull();
    }

    @Test void trustedBackgroundJobRemainsTheOperatorEvenWithAnAmbientUser() {
        try (var user = CurrentUserContext.use(actor); var job = TenantContext.backgroundJob("test.job")) {
            var event = event(null);
            assertThat(event.operatorType()).isEqualTo("BACKGROUND_JOB");
            assertThat(event.operatorId()).isEqualTo(TenantContext.systemOperatorId().orElseThrow());
        }
    }

    private RuntimeEvent event(String action) {
        return RuntimeEvent.of(RuntimeEventType.AFTER_CREATE, "test.records", "record", "r", action,
                null, false, RuntimeMutationSource.BUSINESS, Map.of());
    }
}
