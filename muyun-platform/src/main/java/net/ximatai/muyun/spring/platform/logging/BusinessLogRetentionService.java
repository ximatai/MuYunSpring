package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionStore;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Governs persisted retention policy and delegates bounded destructive work to the storage adapter. */
public final class BusinessLogRetentionService {
    private final BusinessLogRetentionStore retentionStore;
    private final BusinessLogRetentionPolicyStore policyStore;
    private final Clock clock;

    public BusinessLogRetentionService(BusinessLogRetentionStore retentionStore,
                                       BusinessLogRetentionPolicyStore policyStore) {
        this(retentionStore, policyStore, Clock.systemUTC());
    }

    public BusinessLogRetentionService(BusinessLogRetentionStore retentionStore,
                                       BusinessLogRetentionPolicyStore policyStore,
                                       Clock clock) {
        this.retentionStore = Objects.requireNonNull(retentionStore, "retentionStore must not be null");
        this.policyStore = Objects.requireNonNull(policyStore, "policyStore must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<BusinessLogRetentionPolicy> policies(BusinessLogReadScope scope) {
        requirePlatformScope(scope);
        return effectivePolicies();
    }

    public BusinessLogRetentionPolicy updatePolicy(BusinessLogEventType eventType,
                                                    boolean automaticCleanupEnabled,
                                                    int retentionDays,
                                                    String updatedBy,
                                                    BusinessLogReadScope scope) {
        requirePlatformScope(scope);
        return policyStore.saveRetentionPolicy(new BusinessLogRetentionPolicy(eventType,
                automaticCleanupEnabled, retentionDays, clock.instant(), updatedBy));
    }

    /** Executes every currently enabled policy; intended for the scheduler adapter. */
    public List<BusinessLogRetentionRunResult> purgeEnabled(BusinessLogRetentionExecutionLimits limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        List<BusinessLogRetentionRunResult> results = new ArrayList<>();
        for (BusinessLogRetentionPolicy policy : effectivePolicies()) {
            if (policy.automaticCleanupEnabled()) {
                results.add(purge(policy, limits));
            }
        }
        return List.copyOf(results);
    }

    /** Immediately applies the configured cutoff even when automatic cleanup is disabled. */
    public BusinessLogRetentionRunResult purgeNow(BusinessLogEventType eventType,
                                                  BusinessLogRetentionExecutionLimits limits,
                                                  BusinessLogReadScope scope) {
        requirePlatformScope(scope);
        BusinessLogRetentionPolicy policy = effectivePolicies().stream()
                .filter(candidate -> candidate.eventType() == Objects.requireNonNull(eventType,
                        "eventType must not be null"))
                .findFirst()
                .orElseThrow();
        return purge(policy, Objects.requireNonNull(limits, "limits must not be null"));
    }

    private BusinessLogRetentionRunResult purge(BusinessLogRetentionPolicy policy,
                                                BusinessLogRetentionExecutionLimits limits) {
        BusinessLogRetentionResult result = retentionStore.purge(new BusinessLogRetentionRequest(
                clock.instant().minus(Duration.ofDays(policy.retentionDays())),
                Set.of(policy.eventType()),
                limits.batchSize(),
                limits.maximumBatchesPerPolicy()));
        return new BusinessLogRetentionRunResult(policy.eventType(), policy.retentionDays(), result);
    }

    private List<BusinessLogRetentionPolicy> effectivePolicies() {
        EnumMap<BusinessLogEventType, BusinessLogRetentionPolicy> policies = new EnumMap<>(BusinessLogEventType.class);
        for (BusinessLogEventType type : BusinessLogEventType.values()) {
            policies.put(type, BusinessLogRetentionPolicy.defaultDisabled(type));
        }
        for (BusinessLogRetentionPolicy policy : policyStore.findRetentionPolicies()) {
            if (policy != null) {
                policies.put(policy.eventType(), policy);
            }
        }
        return policies.values().stream()
                .sorted(Comparator.comparingInt(policy -> policy.eventType().ordinal()))
                .toList();
    }

    private static void requirePlatformScope(BusinessLogReadScope scope) {
        if (scope == null || !scope.isPlatformScope()) {
            throw new PlatformAccessDeniedException("只有平台管理员可以治理业务日志留存策略");
        }
    }
}
