package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionStore;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessLogRetentionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    private static final BusinessLogRetentionExecutionLimits LIMITS =
            new BusinessLogRetentionExecutionLimits(500, 4);

    @Test
    void shouldApplyOnlyEnabledRuntimePoliciesWithExclusiveTypeScopedCutoffs() {
        List<BusinessLogRetentionRequest> captured = new ArrayList<>();
        BusinessLogRetentionStore retentionStore = request -> {
            captured.add(request);
            return new BusinessLogRetentionResult(request.occurredBefore(), 17, 2,
                    BusinessLogRetentionResult.Status.COMPLETE);
        };
        InMemoryPolicyStore policies = new InMemoryPolicyStore(List.of(
                policy(BusinessLogEventType.LOGIN, true, 180),
                policy(BusinessLogEventType.ACTION, false, 30),
                policy(BusinessLogEventType.REQUEST_ERROR, true, 14)));
        var service = new BusinessLogRetentionService(retentionStore, policies, CLOCK);

        List<BusinessLogRetentionRunResult> results = service.purgeEnabled(LIMITS);

        assertThat(captured).containsExactly(
                new BusinessLogRetentionRequest(Instant.parse("2026-03-25T00:00:00Z"),
                        java.util.Set.of(BusinessLogEventType.LOGIN), 500, 4),
                new BusinessLogRetentionRequest(Instant.parse("2026-09-07T00:00:00Z"),
                        java.util.Set.of(BusinessLogEventType.REQUEST_ERROR), 500, 4));
        assertThat(results).extracting(BusinessLogRetentionRunResult::eventType)
                .containsExactly(BusinessLogEventType.LOGIN, BusinessLogEventType.REQUEST_ERROR);
    }

    @Test
    void shouldExposeDefaultsAndPersistAdministratorChangesOnlyToPlatformScope() {
        InMemoryPolicyStore policies = new InMemoryPolicyStore(List.of(
                policy(BusinessLogEventType.LOGIN, false, 90)));
        var service = new BusinessLogRetentionService(BusinessLogRetentionServiceTest::complete, policies, CLOCK);

        assertThat(service.policies(BusinessLogReadScope.platform())).hasSize(4);
        assertThat(service.policies(BusinessLogReadScope.platform()))
                .filteredOn(policy -> policy.eventType() == BusinessLogEventType.PAGE_ACCESS)
                .singleElement()
                .extracting(BusinessLogRetentionPolicy::retentionDays,
                        BusinessLogRetentionPolicy::automaticCleanupEnabled)
                .containsExactly(180, false);

        BusinessLogRetentionPolicy updated = service.updatePolicy(BusinessLogEventType.ACTION,
                true, 45, 0, "admin", BusinessLogReadScope.platform());

        assertThat(updated.updatedAt()).isEqualTo(CLOCK.instant());
        assertThat(updated.updatedBy()).isEqualTo("admin");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(policies.saved).isEqualTo(updated);
        assertThatThrownBy(() -> service.updatePolicy(BusinessLogEventType.ACTION,
                false, 60, 0, "stale-admin", BusinessLogReadScope.platform()))
                .hasMessageContaining("已被其他管理员修改");
        assertThatThrownBy(() -> service.updatePolicy(BusinessLogEventType.ACTION,
                true, 45, 0, "tenant-admin", BusinessLogReadScope.tenant("t1")))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test
    void shouldAllowPlatformAdministratorToRunDisabledPolicyImmediately() {
        List<BusinessLogRetentionRequest> captured = new ArrayList<>();
        var service = new BusinessLogRetentionService(request -> {
            captured.add(request);
            return complete(request);
        }, new InMemoryPolicyStore(List.of(policy(BusinessLogEventType.ACTION, false, 30))), CLOCK);

        BusinessLogRetentionRunResult run = service.purgeNow(BusinessLogEventType.ACTION, LIMITS,
                BusinessLogReadScope.platform());

        assertThat(run.retentionDays()).isEqualTo(30);
        assertThat(captured).singleElement().satisfies(request -> {
            assertThat(request.occurredBefore()).isEqualTo(Instant.parse("2026-08-22T00:00:00Z"));
            assertThat(request.eventTypes()).containsExactly(BusinessLogEventType.ACTION);
        });
        assertThatThrownBy(() -> service.purgeNow(BusinessLogEventType.ACTION, LIMITS,
                BusinessLogReadScope.organization("t1", java.util.Set.of("o1"))))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test
    void shouldRunOneOffCleanupWithDraftCutoffWithoutPersistingThePolicy() {
        List<BusinessLogRetentionRequest> captured = new ArrayList<>();
        InMemoryPolicyStore policies = new InMemoryPolicyStore(List.of(
                policy(BusinessLogEventType.ACTION, false, 180)));
        var service = new BusinessLogRetentionService(request -> {
            captured.add(request);
            return complete(request);
        }, policies, CLOCK);

        BusinessLogRetentionRunResult run = service.purgeNow(BusinessLogEventType.ACTION, 45, LIMITS,
                BusinessLogReadScope.platform());

        assertThat(run.retentionDays()).isEqualTo(45);
        assertThat(captured).singleElement().satisfies(request ->
                assertThat(request.occurredBefore()).isEqualTo(Instant.parse("2026-08-07T00:00:00Z")));
        assertThat(policies.saved).isNull();
        assertThatThrownBy(() -> service.purgeNow(BusinessLogEventType.ACTION, 0, LIMITS,
                BusinessLogReadScope.platform())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUnsafeBusinessAndExecutionSettings() {
        assertThatThrownBy(() -> policy(BusinessLogEventType.LOGIN, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessLogRetentionExecutionLimits(10_001, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessLogRetentionExecutionLimits(1_000, 1_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BusinessLogRetentionPolicy policy(BusinessLogEventType type, boolean enabled, int days) {
        return new BusinessLogRetentionPolicy(type, enabled, days, 0, CLOCK.instant(), "admin");
    }

    private static BusinessLogRetentionResult complete(BusinessLogRetentionRequest request) {
        return new BusinessLogRetentionResult(request.occurredBefore(), 0, 1,
                BusinessLogRetentionResult.Status.COMPLETE);
    }

    private static final class InMemoryPolicyStore implements BusinessLogRetentionPolicyStore {
        private final List<BusinessLogRetentionPolicy> policies;
        private BusinessLogRetentionPolicy saved;

        private InMemoryPolicyStore(List<BusinessLogRetentionPolicy> policies) {
            this.policies = new ArrayList<>(policies);
        }

        @Override
        public List<BusinessLogRetentionPolicy> findRetentionPolicies() {
            return List.copyOf(policies);
        }

        @Override
        public BusinessLogRetentionPolicy saveRetentionPolicy(BusinessLogRetentionPolicy policy,
                                                                long expectedVersion) {
            BusinessLogRetentionPolicy current = policies.stream()
                    .filter(candidate -> candidate.eventType() == policy.eventType())
                    .findFirst()
                    .orElse(BusinessLogRetentionPolicy.defaultDisabled(policy.eventType()));
            if (expectedVersion != current.version() || policy.version() != expectedVersion + 1) {
                throw new net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyConflictException(
                        policy.eventType());
            }
            saved = policy;
            policies.removeIf(candidate -> candidate.eventType() == policy.eventType());
            policies.add(policy);
            return policy;
        }
    }
}
