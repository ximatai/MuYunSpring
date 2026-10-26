package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.job.JobService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Tenant-level acceptance of identity providers. */
@Service
@ConditionalOnExternalIdentityFederation
public class TenantIdentityPolicyService extends TenantActiveScopedService<TenantIdentityPolicy> implements
        EnableAbility<TenantIdentityPolicy>,
        SortAbility<TenantIdentityPolicy>,
        ReferenceAbility<TenantIdentityPolicy>,
        QueryAbility<TenantIdentityPolicy> {
    public static final String MODULE_ALIAS = "iam.tenant_identity_policy";

    private final IdentityProviderService identityProviderService;
    private final DirectorySyncConfiguration directory;
    private final JobService jobs;

    public TenantIdentityPolicyService(
            TenantIdentityPolicyDao dao,
            ActiveTenantVerifier activeTenantVerifier,
            IdentityProviderService identityProviderService,
            DirectorySyncConfiguration directory,
            JobService jobs) {
        super(MODULE_ALIAS, TenantIdentityPolicy.class, dao, activeTenantVerifier);
        this.identityProviderService =
                Objects.requireNonNull(identityProviderService, "identityProviderService");
        this.directory = Objects.requireNonNull(directory);
        this.jobs = Objects.requireNonNull(jobs);
    }

    @Override
    public void normalizeBeforeMutation(TenantIdentityPolicy policy) {
        // The record title follows the provider so lists and references read naturally.
        policy.setTitle(identityProviderService.findUsable(policy.getProviderId())
                .map(IdentityProvider::getTitle)
                .orElse(policy.getProviderId()));
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, TenantIdentityPolicy.class,
                List.of("id", "providerId", "loginEnabled", "bindingEnabled", "provisioningMode", "enabled",
                        "sortOrder", "createdAt", "updatedAt"),
                Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(TenantIdentityPolicy policy) {
        requireSupportedProvisioning(policy);
        directory.maintain(policy, null);
    }

    @Override
    public void beforeUpdate(TenantIdentityPolicy policy, TenantIdentityPolicy existing) {
        requireSupportedProvisioning(policy);
        directory.maintain(policy, existing);
    }

    @Override public String insert(TenantIdentityPolicy policy) {
        directory.prepare(policy);
        return super.insert(policy);
    }
    @Override public int update(TenantIdentityPolicy policy) {
        var retained = selectActiveRaw(policy.getId());
        if (retained == null
                || !Objects.equals(retained.getSyncRoots(), policy.getSyncRoots())
                || !Objects.equals(retained.getProviderId(), policy.getProviderId())
                || !Objects.equals(
                        retained.getSyncScopeConfirmed(), policy.getSyncScopeConfirmed()))
            directory.prepare(policy);
        return super.update(policy);
    }
    @Override public void beforeDelete(String id) {
        var policy = selectActiveRaw(id);
        if (policy != null) directory.deletePlan(policy);
    }
    /** Shared trusted entry for both operator actions; jobs always run as tenant SYSTEM. */
    public String synchronize(String id, boolean preview) {
        return inMutationTransaction(() -> {
            var policy = selectActiveRaw(id);
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) throw new IllegalArgumentException("Directory policy unavailable");
            directory.lock(policy);
            policy = selectActiveRaw(id);
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) throw new IllegalArgumentException("Directory policy unavailable");
            if (preview) directory.startScopePreview(policy);
            String mode = preview ? (policy.getPendingSyncScopeId() == null ? "PREVIEW" : "SCOPE_PREVIEW") : "SYNC";
            String scope = "SCOPE_PREVIEW".equals(mode) ? policy.getPendingSyncScopeId() : policy.getSyncScopeId();
            String deduplicationKey = policy.getId() + ":" + mode + ":" + scope;
            return jobs.enqueue(DirectorySyncJob.TYPE, policy.getTenantId(),
                    DirectorySyncConfiguration.input(policy, mode), deduplicationKey).getId();
        });
    }

    /** Returns the reviewed immutable proposal and its persisted impact summary. */
    public Map<String, Object> scopeImpact(String id) {
        var policy = select(id);
        if (policy == null) throw new IllegalArgumentException("Directory policy unavailable");
        return directory.impactReport(policy);
    }

    /** Confirmation is a separate permissioned management action bound to the reviewed revision. */
    public String confirmScope(String id, String scopeId, String reportRunId) {
        return inMutationTransaction(() -> {
            var policy = selectActiveRaw(id);
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled()))
                throw new IllegalArgumentException("Directory policy unavailable");
            // Acquire the topic lock before reading again: a save/preview may have won the race.
            directory.lock(policy);
            policy = selectActiveRaw(id);
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) throw new IllegalArgumentException("Directory policy unavailable");
            directory.confirmScope(policy, scopeId, reportRunId);
            DirectorySyncStore.save((TenantIdentityPolicyDao) getDao(), policy);
            // Confirmation authorizes applying the proposal, including an empty scope with no plan.
            return jobs.enqueue(DirectorySyncJob.TYPE, policy.getTenantId(),
                    DirectorySyncConfiguration.input(policy, "SYNC"),
                    policy.getId() + ":SYNC:" + policy.getSyncScopeId()).getId();
        });
    }

    /** Enabled policies of a tenant, read in that tenant's scope for the public sign-in entry. */
    public List<TenantIdentityPolicy> enabledPolicies(String tenantId) {
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return list(Criteria.of().eq("enabled", true), new PageRequest(0, 100));
        }
    }

    public Optional<TenantIdentityPolicy> enabledPolicy(String tenantId, String providerId) {
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return Optional.ofNullable(findOne(Criteria.of().eq("providerId", providerId).eq("enabled", true)));
        }
    }

    private static void requireSupportedProvisioning(TenantIdentityPolicy policy) {
        if (policy.getSyncExpression() == null || policy.getSyncExpression().isBlank())
            policy.setSyncExpression("0 0 0 * * *");
        if (policy.getSourceStatusMaxAgeSeconds() == null) policy.setSourceStatusMaxAgeSeconds(259200);
        if (policy.getSourceStatusMaxAgeSeconds() < 60 || policy.getSourceStatusMaxAgeSeconds() > 2592000)
            throw new IllegalArgumentException("Source status maximum age must be between 60 seconds and 30 days");
        // AUTO and REVIEW are reserved in the model; accepting them before they work would silently deny users.
        if (policy.getProvisioningMode() != null && policy.getProvisioningMode() != ProvisioningMode.DISABLED) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.tenant-identity-policy.provisioning-mode-is-not-supported-yet",
                    "only disabled provisioning is supported in this version"), Map.of());
        }
    }
}
