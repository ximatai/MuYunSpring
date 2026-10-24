package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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

    public TenantIdentityPolicyService(TenantIdentityPolicyDao dao, ActiveTenantVerifier activeTenantVerifier,
                                       IdentityProviderService identityProviderService) {
        super(MODULE_ALIAS, TenantIdentityPolicy.class, dao, activeTenantVerifier);
        this.identityProviderService = java.util.Objects.requireNonNull(identityProviderService,
                "identityProviderService");
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
    }

    @Override
    public void beforeUpdate(TenantIdentityPolicy policy, TenantIdentityPolicy existing) {
        requireSupportedProvisioning(policy);
    }

    /** Enabled policies of a tenant, read in that tenant's scope for the public sign-in entry. */
    public List<TenantIdentityPolicy> enabledPolicies(String tenantId) {
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return list(Criteria.of().eq("enabled", true), new net.ximatai.muyun.database.core.orm.PageRequest(0, 100));
        }
    }

    public Optional<TenantIdentityPolicy> enabledPolicy(String tenantId, String providerId) {
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return Optional.ofNullable(findOne(Criteria.of().eq("providerId", providerId).eq("enabled", true)));
        }
    }

    private static void requireSupportedProvisioning(TenantIdentityPolicy policy) {
        // AUTO and REVIEW are reserved in the model; accepting them before they work would silently deny users.
        if (policy.getProvisioningMode() != null && policy.getProvisioningMode() != ProvisioningMode.DISABLED) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.tenant-identity-policy.provisioning-mode-is-not-supported-yet",
                    "only disabled provisioning is supported in this version"), Map.of());
        }
    }
}
