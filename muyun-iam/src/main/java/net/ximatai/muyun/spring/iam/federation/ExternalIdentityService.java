package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Bindings between external identities and local accounts. Bindings are only created by verified
 * federation flows, never through the generic create or update entries; administrators may remove them.
 */
@Service
@ConditionalOnExternalIdentityFederation
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE})
public class ExternalIdentityService extends TenantActiveScopedService<ExternalIdentity> implements
        QueryAbility<ExternalIdentity> {
    public static final String MODULE_ALIAS = "iam.external_identity";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    public ExternalIdentityService(ExternalIdentityDao dao, ActiveTenantVerifier activeTenantVerifier) {
        super(MODULE_ALIAS, ExternalIdentity.class, dao, activeTenantVerifier);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ExternalIdentity.class,
                List.of("id", "userId", "providerId", "externalUserId", "displayName", "bindingSource", "boundAt",
                        "lastAuthenticatedAt", "createdAt"),
                Sort.desc("boundAt"));
    }

    /** Looks up the binding of an external identity in the current tenant scope. */
    Optional<ExternalIdentity> findByExternalIdentity(String identityNamespace, String externalEnterpriseId,
                                                      String externalUserId) {
        return Optional.ofNullable(findOne(Criteria.of()
                .eq("identityNamespace", identityNamespace)
                .eq("externalEnterpriseId", externalEnterpriseId)
                .eq("externalUserId", externalUserId)));
    }

    /** Stamps a successful sign-in through the binding; internal state, not a business edit. */
    void recordAuthentication(String identityId, java.time.Instant at) {
        net.ximatai.muyun.spring.ability.VersionedRecordMutation.update(getDao(), () -> selectActiveRaw(identityId),
                binding -> binding.setLastAuthenticatedAt(at));
    }

    /** Bindings of one account in the current tenant scope. */
    public List<ExternalIdentity> bindingsOfUser(String userId) {
        return list(Criteria.of().eq("userId", userId), ALL);
    }
}
