package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Deletes a tenant-owned binding and its owned target under an authorized source action.
 * The relation is a trusted server declaration; target identity comes only from the persisted binding.
 * The binding lookup runs only after source authorization, in the source record’s tenant.
 * No authorization proof or unscoped callback escapes this operation.
 */
public record RelatedRecordDeletion<S extends EntityContract, B extends EntityContract, T extends EntityContract>(
        DataScopeAbility<S> source, CrudAbility<B> bindings, Function<B, String> sourceId,
        CrudAbility<T> target, Function<B, String> targetId) {

    public RelatedRecordDeletion {
        Objects.requireNonNull(source);
        Objects.requireNonNull(bindings);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(target);
        Objects.requireNonNull(targetId);
    }

    public Result<B> delete(String ownerId, Supplier<String> bindingIdLookup, ActionExecutionPolicy policy,
                             ActionExecutionPolicyService authorization) {
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(bindingIdLookup);
        Objects.requireNonNull(policy);
        if (ownerId == null || ownerId.isBlank() || !policy.requiresLogin()) {
            throw new IllegalArgumentException("related deletion requires an authenticated source record action");
        }
        var actor = CurrentUserContext.currentUser();
        var acting = ActingContextHolder.current();
        Runnable requireSameActor = () -> {
            if (!actor.equals(CurrentUserContext.currentUser()) || !acting.equals(ActingContextHolder.current())) {
                throw new PlatformAccessDeniedException("关联删除期间操作者不能改变");
            }
        };
        try (var actorScope = CurrentUserContext.use(actor.orElseThrow(() ->
                new PlatformAccessDeniedException("关联删除要求已登录操作者")));
             var actingScope = acting.map(ActingContextHolder::use).orElse(null)) {
            return PlatformAbilityDispatcher.inMutationTransaction(() -> {
                if (!TransactionScopeSupport.isTransactionActive()) {
                    throw new IllegalStateException("related deletion requires an active transaction");
                }
                authorization.requireRecordAction(ActionExecutionContext.ofPolicy(
                        source.getModuleAlias(), policy, Set.of(ownerId), actor));
                requireSameActor.run();
                var scope = source.requireRecordScopeResult(policy, Set.of(ownerId));
                S owner = source.withDataScopeTenant(scope, () -> source.selectActiveRaw(ownerId));
                if (owner == null || owner.getTenantId() == null || owner.getTenantId().isBlank()) {
                    throw new PlatformAccessDeniedException("关联删除要求可访问的租户内来源记录");
                }
                try (var ignored = TenantContext.use(owner.getTenantId())) {
                    Runnable requireBindingContext = () -> {
                        requireSameActor.run();
                        if (!TenantContext.currentTenantId().filter(owner.getTenantId()::equals).isPresent()
                                || TenantContext.tenantFilterBypassed()) {
                            throw new PlatformAccessDeniedException("关联删除期间租户范围不能改变");
                        }
                    };
                    String bindingId = bindingIdLookup.get();
                    requireBindingContext.run();
                    if (bindingId == null) return new Result<>(null, null, 0);
                    B binding = bindings.selectActiveRaw(bindingId);
                    if (binding == null || !ownerId.equals(sourceId.apply(binding))
                            || !owner.getTenantId().equals(binding.getTenantId())) {
                        throw new PlatformAccessDeniedException("关联记录不属于当前来源记录");
                    }
                    String relatedId = Objects.requireNonNull(targetId.apply(binding), "bound target id");
                    // Read ownership as a storage fact; never expose this unscoped record to the caller.
                    T related = target.getDao().findById(relatedId);
                    if (related != null && !owner.getTenantId().equals(related.getTenantId())) {
                        throw new PlatformAccessDeniedException("关联目标与来源记录必须属于同一租户");
                    }
                    Integer bindingVersion = Objects.requireNonNull(binding.getVersion(), "binding version");
                    Integer targetVersion = related == null ? null : Objects.requireNonNull(related.getVersion(), "target version");
                    requireBindingContext.run();
                    if (bindings.delete(bindingId, bindingVersion) != 1) {
                        throw new OptimisticLockException("关联记录发生变化，请重试");
                    }
                    requireBindingContext.run();
                    int deleted = 0;
                    if (related != null) {
                        var proof = new VerifiedMutationScope(target, PlatformAction.DELETE, Set.of(relatedId),
                                DataScopeCriteriaResult.restricted(Criteria.of().eq("id", relatedId)
                                        .eq("tenantId", owner.getTenantId())));
                        deleted = VerifiedMutationScopeExecutor.execute(target, PlatformAction.DELETE,
                                Set.of(relatedId), proof, () -> target.delete(relatedId, targetVersion));
                        if (deleted != 1) throw new OptimisticLockException("关联目标发生变化，请重试");
                    }
                    requireBindingContext.run();
                    return new Result<>(binding, relatedId, deleted);
                }
            });
        } finally {
            if (acting.isEmpty()) ActingContextHolder.clear();
        }
    }

    public record Result<B>(B binding, String targetId, int targetDeleted) {}
}
