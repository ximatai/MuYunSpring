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

    private net.ximatai.muyun.spring.iam.user.UserSessionRevocationService revocations;
    private DirectoryRebindBlockDao rebindBlocks;
    private DirectorySyncStore directory;

    @org.springframework.beans.factory.annotation.Autowired
    void configureLifecycle(net.ximatai.muyun.spring.iam.user.UserSessionRevocationService revocations,
                            DirectoryRebindBlockDao rebindBlocks, DirectorySyncStore directory) {
        this.revocations = revocations;
        this.rebindBlocks = rebindBlocks;
        this.directory = directory;
    }

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

    <R> R inAdmissionTransaction(java.util.function.Supplier<R> work) {
        return inMutationTransaction(work);
    }

    /** Stamps a successful sign-in through the binding; internal state, not a business edit. */
    void recordAuthentication(String identityId, java.time.Instant at) {
        net.ximatai.muyun.spring.ability.VersionedRecordMutation.update(getDao(), () -> selectActiveRaw(identityId),
                binding -> binding.setLastAuthenticatedAt(at));
    }

    void lockSource(String tenantId, String providerId) {
        directory.lock(java.util.Map.of("tenantId", tenantId, "providerId", providerId));
    }

    /** Same tenant/source lock as synchronization, before CRUD takes the binding row lock. */
    @Override
    public void beforeDelete(String id) {
        var binding = selectActiveRaw(id);
        if (binding != null) {
            lockSource(binding.getTenantId(), binding.getProviderId());
        }
    }

    @Override
    public void afterDelete(String id, ExternalIdentity binding, int deleted) {
        if (deleted < 1 || binding == null) return;
        if (!isAutoRebindBlocked(binding.getProviderId(), binding.getExternalUserId())) {
            var block = new DirectoryRebindBlock();
            block.setTenantId(binding.getTenantId());
            block.setProviderId(binding.getProviderId());
            block.setExternalUserId(binding.getExternalUserId());
            DirectorySyncStore.insert(rebindBlocks, block);
        }
        revocations.revokeUserSessions(binding.getUserId(), "external identity unbound");
    }

    public boolean isAutoRebindBlocked(String providerId, String externalUserId) {
        if (rebindBlocks == null) return false;
        return !rebindBlocks.query(Criteria.of().eq("tenantId", net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElseThrow())
                .eq("providerId", providerId).eq("externalUserId", externalUserId), new PageRequest(0, 1)).isEmpty();
    }

    /** Bindings of one account in the current tenant scope. */
    public List<ExternalIdentity> bindingsOfUser(String userId) {
        return list(Criteria.of().eq("userId", userId), ALL);
    }
}
