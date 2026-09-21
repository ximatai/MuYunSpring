package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

/** Persistence boundary for administrator-managed business-log retention policies. */
public interface BusinessLogRetentionPolicyStore {
    /** Returns persisted overrides; callers supply safe defaults for missing event types. */
    List<BusinessLogRetentionPolicy> findRetentionPolicies();

    BusinessLogRetentionPolicy saveRetentionPolicy(BusinessLogRetentionPolicy policy);
}
