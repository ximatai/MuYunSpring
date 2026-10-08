package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.capability.ApprovalCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import java.util.Objects;
import java.util.Set;
import net.ximatai.muyun.spring.common.exception.PlatformException;

/** Exact record binding: an approval command cannot authorize a second record or ordinary update. */
final class ApprovalMutationSupport {
    private record Binding(MutationServiceIdentity service, EntityContract record, String id, String tenantId, ApprovalState state) {}
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private record BusinessBinding(MutationServiceIdentity service, EntityContract record, String id, String tenantId) {}
    private static final ThreadLocal<BusinessBinding> BUSINESS = new ThreadLocal<>();
    private ApprovalMutationSupport() {}

    static <T extends EntityContract & ApprovalCapable> int updateBusiness(ApprovalAbility<T> service, T record) {
        Objects.requireNonNull(record, "record");
        if (!service.supportsApproval()) throw new IllegalArgumentException("module does not support approval");
        BusinessBinding previous = BUSINESS.get();
        T stored = service.selectActiveRaw(record.getId());
        BUSINESS.set(new BusinessBinding(service.mutationServiceIdentity(), record, record.getId(),
                stored == null ? record.getTenantId() : stored.getTenantId()));
        try { return service.update(record); }
        finally { if (previous == null) BUSINESS.remove(); else BUSINESS.set(previous); }
    }

    static void requireEditable(CrudAbility<?> service, EntityContract incoming, EntityContract existing) {
        if (!(service instanceof ApprovalAbility<?> ability) || !ability.supportsApproval()
                || !(existing instanceof ApprovalCapable approval)) return;
        String status = approval.getApprovalStatus();
        if (!"processing".equalsIgnoreCase(status) && !"approved".equalsIgnoreCase(status)) return;
        Binding summary = CURRENT.get();
        if (summary != null && summary.service().equals(service.mutationServiceIdentity()) && summary.record() == incoming) return;
        BusinessBinding business = BUSINESS.get();
        if (business != null && business.service().equals(service.mutationServiceIdentity()) && business.record() == incoming) return;
        throw new PlatformException("审批中或已批准的业务不可直接修改，请通过当前任务办理指引更新");
    }

    static <T extends EntityContract & ApprovalCapable> int update(ApprovalAbility<T> service, String id,
                                                                  ActionExecutionPolicy policy, ApprovalState state) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(state, "state");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("approval record id is required");
        if (!service.supportsApproval()) throw new IllegalArgumentException("module does not support approval: " + service.getModuleAlias());
        return PlatformAbilityDispatcher.inMutationTransaction(() -> {
            try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(
                    service.getModuleAlias(), policy, Set.of(id), CurrentUserContext.currentUser()))) {
                if (service instanceof DataScopeAbility<?> scoped && policy.requiresDataScope()) {
                    var proof = scoped.verifyMutationScope(service.getModuleAlias(), policy, PlatformAction.UPDATE, Set.of(id));
                    return VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE, Set.of(id), proof,
                            () -> updateRecord(service, id, state));
                }
                return updateRecord(service, id, state);
            }
        });
    }

    private static <T extends EntityContract & ApprovalCapable> int updateRecord(ApprovalAbility<T> service,
                                                                                String id, ApprovalState state) {
        T stored = service.selectActiveRaw(id);
        if (stored == null) return 0;
        // A workflow summary belongs to its explicitly bound tenant even for a cross-tenant administrator.
        if (!Objects.equals(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null), stored.getTenantId())) return 0;
        PlatformAbilityDispatcher.requireMutationContext(service, stored);
        T draft = service.copyForApprovalMutation(stored);
        if (service instanceof FieldProtectionAbility<?> protectedService) {
            @SuppressWarnings("unchecked") var protection = (FieldProtectionAbility<T>) protectedService;
            protection.restoreProtectedFieldsFromStorage(draft);
        }
        state.apply(draft);
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(service.mutationServiceIdentity(), draft, id, stored.getTenantId(), state));
        try { return service.update(draft); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    private static void requireIdentity(EntityContract record, String id, String tenantId) {
        if (!Objects.equals(record.getId(), id) || !Objects.equals(record.getTenantId(), tenantId))
            throw new PlatformException("审批命令绑定的记录身份或租户不可修改");
    }

    static void retain(CrudAbility<?> service, EntityContract incoming, EntityContract existing) {
        Binding binding = CURRENT.get();
        if (binding != null && binding.service().equals(service.mutationServiceIdentity()) && binding.record() == incoming)
            requireIdentity(incoming, binding.id(), binding.tenantId());
        BusinessBinding business = BUSINESS.get();
        if (business != null && business.service().equals(service.mutationServiceIdentity()) && business.record() == incoming)
            requireIdentity(incoming, business.id(), business.tenantId());
        if (!(incoming instanceof ApprovalCapable approval)) return;
        if (binding != null && binding.service().equals(service.mutationServiceIdentity()) && binding.record() == incoming) {
            binding.state().apply(approval);
        } else {
            (existing instanceof ApprovalCapable previous ? ApprovalState.read(previous) : ApprovalState.empty()).apply(approval);
        }
    }
}
