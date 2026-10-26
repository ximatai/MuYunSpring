package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Non-HTTP boundary shared by scheduled static and metadata actions. */
@Service
public class ModuleActionInvoker {
    private final PlatformModuleService modules;
    private final PlatformModuleActionService actions;
    private final ActionExecutionPolicyService policies;
    private final ObjectProvider<DynamicRecordService> records;
    private final ObjectProvider<ScheduledModuleAction> contributions;
    private final RuntimeEventPublisher events;

    public ModuleActionInvoker(PlatformModuleService modules, PlatformModuleActionService actions,
            ActionExecutionPolicyService policies, ObjectProvider<DynamicRecordService> records,
            ObjectProvider<ScheduledModuleAction> contributions, RuntimeEventPublisher events) {
        this.modules = Objects.requireNonNull(modules); this.actions = Objects.requireNonNull(actions);
        this.policies = Objects.requireNonNull(policies); this.records = Objects.requireNonNull(records);
        this.contributions = Objects.requireNonNull(contributions); this.events = Objects.requireNonNull(events);
    }

    private record Target(PlatformModuleAction action, boolean dynamic) { }
    public PlatformModuleAction validateTarget(String module, String code) {
        return target(module, code).action();
    }
    private Target target(String module, String code) {
        PlatformModule target;
        try (var global = TenantContext.system("resolve scheduled action module")) { target = modules.select(module); }
        if (target == null || !Boolean.TRUE.equals(target.getEnabled())
                || target.getTenantId() != null && !Objects.equals(target.getTenantId(), TenantContext.currentTenantId().orElse(null))) throw denied();
        var action = actions.findByModuleAliasAndActionCode(module, code);
        if (action == null || !Boolean.TRUE.equals(action.getEnabled())
                || action.getActionLevel() != EntityActionLevel.LIST
                || Boolean.TRUE.equals(action.getFormSupported())
                || action.getExecutorType() == EntityActionExecutorType.DIALOG) throw denied();
        if (target.getModuleKind() == ModuleKind.DYNAMIC) {
            if (!Boolean.TRUE.equals(action.getSchedulable()) || action.isBindingPending()
                    || action.getExecutorType() != EntityActionExecutorType.SERVICE) throw denied();
        } else {
            contribution(module, code);
            // Availability expressions require the dynamic record/form evaluator.
            if (action.getAvailableExpression() != null && !action.getAvailableExpression().isBlank()) throw denied();
        }
        return new Target(action, target.getModuleKind() == ModuleKind.DYNAMIC);
    }

    public record ScheduledTarget(String moduleAlias, String actionCode) { }
    static final int VISIBILITY_BATCH_SIZE = 100;
    static final int VISIBILITY_TARGET_BUDGET = 256;

    /**
     * Query visibility is target authorization, independent of stored plan parameters.
     * Work is bounded by the action catalog, never by the number of scheduled plans.
     * Exhaustion fails the request instead of returning a truncated scope or an incorrect total.
     */
    public List<ScheduledTarget> authorizedScheduledTargets() {
        var candidates = new LinkedHashSet<ScheduledTarget>();
        var declared = contributions.stream().limit(VISIBILITY_TARGET_BUDGET + 1L).toList();
        int work = declared.size();
        requireVisibilityBudget(work);
        declared.forEach(item -> candidates.add(new ScheduledTarget(item.moduleAlias(), item.actionCode())));
        String tenant = TenantContext.currentTenantId().orElse(null);
        var visibleModules = tenant == null
                ? SqlSubQuery.of("select id from platform_module where enabled=true and (deleted=false or deleted is null) and tenant_id is null", Map.of())
                : SqlSubQuery.of("select id from platform_module where enabled=true and (deleted=false or deleted is null) and (tenant_id is null or tenant_id=:visibilityTenant)", Map.of("visibilityTenant", tenant));
        String after = null;
        while (true) {
            List<PlatformModuleAction> batch;
            try (var global = TenantContext.system("resolve scheduled action visibility catalog")) {
                var criteria = Criteria.of().isNull("tenantId").eq("enabled", true)
                        .eq("schedulable", true).eq("actionLevel", EntityActionLevel.LIST)
                        .inSubQuery("moduleAlias", visibleModules);
                if (after != null) criteria.gt("id", after);
                batch = actions.list(criteria, new PageRequest(0, Math.min(VISIBILITY_BATCH_SIZE,
                        VISIBILITY_TARGET_BUDGET - work + 1)), Sort.asc("id"));
            }
            if (batch.isEmpty()) break;
            work += batch.size();
            requireVisibilityBudget(work);
            batch.forEach(item -> candidates.add(new ScheduledTarget(item.getModuleAlias(), item.getActionCode())));
            after = batch.getLast().getId();
        }
        var visible = new ArrayList<ScheduledTarget>();
        for (var candidate : candidates) {
            try {
                target(candidate.moduleAlias(), candidate.actionCode());
                policies.authorize(ActionExecutionContext.ofPolicy(candidate.moduleAlias(),
                        actions.requireExecutionPolicy(candidate.moduleAlias(), candidate.actionCode()),
                        Set.of(), CurrentUserContext.currentUser()));
                visible.add(candidate);
            } catch (PlatformAccessDeniedException denied) {
                // Unavailable or unauthorized targets contribute no rows to the SQL scope.
            }
        }
        return List.copyOf(visible);
    }

    private static void requireVisibilityBudget(int work) {
        if (work > VISIBILITY_TARGET_BUDGET) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.scheduled-action.visibility-budget-exceeded",
                "Scheduled action visibility exceeds the target budget"));
    }

    public void requireAuthorized(String module, String code, Map<String, Object> parameters) {
        var target = target(module, code);
        var context = ActionExecutionContext.ofPolicy(module, actions.requireExecutionPolicy(module, code), Set.of(), CurrentUserContext.currentUser());
        var authorized = context.withAuthorizationResult(policies.authorize(context));
        try (var scope = ActionExecutionContextHolder.use(authorized)) {
            if (!target.dynamic()) contribution(module, code).validate(parameters);
        }
    }

    public Object invoke(String module, String code, Map<String, Object> parameters) {
        var target = target(module, code);
        if (target.dynamic()) {
            policies.authorize(ActionExecutionContext.ofPolicy(module, actions.requireExecutionPolicy(module, code), Set.of(), CurrentUserContext.currentUser()));
            return records.getObject().executeAction(module, code, DynamicActionExecutionRequest.empty().withPayload(parameters));
        }
        var context = ActionExecutionContext.ofPolicy(module, actions.requireExecutionPolicy(module, code), Set.of(), CurrentUserContext.currentUser());
        var authorized = context.withAuthorizationResult(policies.authorize(context));
        try (var scope = ActionExecutionContextHolder.use(authorized)) {
            Object result;
            try {
                var contribution = contribution(module, code);
                contribution.validate(parameters);
                result = contribution.execute(parameters);
            }
            catch (RuntimeException failure) {
                try { events.publish(event(authorized, RuntimeEventType.ACTION_FAILED, target.action().getEntityAlias())); }
                catch (RuntimeException observerFailure) { if (observerFailure != failure) failure.addSuppressed(observerFailure); }
                throw failure;
            }
            events.publishAfterCommit(event(authorized, RuntimeEventType.ACTION_EXECUTED, target.action().getEntityAlias()));
            return result;
        }
    }

    private RuntimeEvent event(ActionExecutionContext context, RuntimeEventType type, String declaredEntity) {
        var auth = context.authorizationResult();
        String entity = declaredEntity == null || declaredEntity.isBlank()
                ? context.moduleAlias().substring(context.moduleAlias().lastIndexOf('.') + 1) : declaredEntity;
        return new RuntimeEvent(null, null, type, context.moduleAlias(), entity, null, context.actionCode(),
                TenantContext.currentTenantId().orElse(null), TenantContext.isSystem(), TenantContext.systemReason().orElse(null),
                auth.operatorId(), auth.operatorType(), auth.decision(), auth.permissionCode(), auth.permissionActionCode(),
                RuntimeMutationSource.ACTION, Map.of(), null, null);
    }
    private ScheduledModuleAction contribution(String module, String code) {
        var matches = contributions.stream().filter(item -> item.moduleAlias().equals(module) && item.actionCode().equals(code)).toList();
        if (matches.size() != 1) throw denied();
        return matches.getFirst();
    }
    private static PlatformAccessDeniedException denied() { return new PlatformAccessDeniedException("Action is not available for scheduled module invocation"); }
}
