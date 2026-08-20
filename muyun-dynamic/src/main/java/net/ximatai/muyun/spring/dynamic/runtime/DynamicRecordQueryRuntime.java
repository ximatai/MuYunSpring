package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Source-neutral execution kernel for the ordinary dynamic-record read surface.
 *
 * <p>It owns data-scope resolution and temporary tenant bypass for query operations. Mutation,
 * relation and reference orchestration deliberately remain in {@link DynamicRecordService}.</p>
 */
final class DynamicRecordQueryRuntime {
    private final DynamicRecordRuntime runtime;
    private final ActionExecutionPolicyService actionExecutionPolicyService;
    private final DataScopeCriteriaService dataScopeCriteriaService;

    DynamicRecordQueryRuntime(DynamicRecordRuntime runtime,
                              ActionExecutionPolicyService actionExecutionPolicyService,
                              DataScopeCriteriaService dataScopeCriteriaService) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.actionExecutionPolicyService = Objects.requireNonNull(actionExecutionPolicyService,
                "actionExecutionPolicyService must not be null");
        this.dataScopeCriteriaService = Objects.requireNonNull(dataScopeCriteriaService,
                "dataScopeCriteriaService must not be null");
    }

    List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria,
                             PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).list(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).list(scoped, sorts));
    }

    List<DynamicRecord> listForAction(String moduleAlias, String entityAlias, PlatformAction action,
                                      Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, action.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).list(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> listForAction(String moduleAlias, String entityAlias, PlatformAction action,
                                      Criteria criteria, Sort... sorts) {
        return withScope(moduleAlias, action.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).list(scoped, sorts));
    }

    PageResult<DynamicRecord> page(String moduleAlias, String entityAlias, Criteria criteria,
                                   PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).pageQuery(scoped, pageRequest, sorts));
    }

    PageResult<DynamicRecord> pageForAction(String moduleAlias, String entityAlias, String actionCode,
                                            Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        DynamicActionDescriptor action = requireAction(moduleAlias, entityAlias, actionCode);
        ActionExecutionPolicy policy = actionPolicy(action);
        actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias, policy, java.util.Set.of(), CurrentUserContext.currentUser()));
        return withScope(moduleAlias, policy, criteria,
                scoped -> entityService(moduleAlias, entityAlias).pageQuery(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> children(String moduleAlias, String entityAlias, String parentId) {
        return withScope(moduleAlias, PlatformAction.TREE.executionPolicy(), Criteria.of(), (scope, scoped) -> {
            if (!scope.restricted()) {
                return entityService(moduleAlias, entityAlias).children(parentId);
            }
            return entityService(moduleAlias, entityAlias).children(scoped, parentId);
        });
    }

    List<DynamicRecord> childrenForAction(String moduleAlias, String entityAlias, String actionCode,
                                          Criteria criteria, String parentId) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DynamicActionDescriptor action = requireAction(moduleAlias, entityAlias, actionCode);
        ActionExecutionPolicy policy = actionPolicy(action);
        actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias, policy, java.util.Set.of(), CurrentUserContext.currentUser()));
        return withScope(moduleAlias, policy, criteria,
                scoped -> entityService(moduleAlias, entityAlias).children(scoped, parentId));
    }

    long count(String moduleAlias, String entityAlias, Criteria criteria) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).count(scoped));
    }

    List<DynamicRecord> sortedList(String moduleAlias, String entityAlias, Criteria criteria) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> entityService(moduleAlias, entityAlias).sortedList(scoped));
    }

    <R> R withQueryReadScope(String moduleAlias, Criteria criteria, Function<Criteria, R> action) {
        Objects.requireNonNull(action, "action must not be null");
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria, action);
    }

    private DynamicActionDescriptor requireAction(String moduleAlias, String entityAlias, String actionCode) {
        DynamicEntityDescriptor entity = runtime.describe(moduleAlias).entities().stream()
                .filter(candidate -> candidate.entityAlias().equals(entityAlias))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic entity: "
                        + moduleAlias + "." + entityAlias));
        return entity.actions().stream()
                .filter(action -> action.code().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action: "
                        + moduleAlias + "." + entityAlias + "." + actionCode));
    }

    private void requireCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        boolean supported = runtime.describe(moduleAlias).entities().stream()
                .filter(entity -> entity.entityAlias().equals(entityAlias))
                .anyMatch(entity -> entity.capabilities().contains(capability.name()));
        if (!supported) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformException(
                    "dynamic entity does not support capability: " + capability);
        }
    }

    private ActionExecutionPolicy actionPolicy(DynamicActionDescriptor action) {
        return new ActionExecutionPolicy(action.code(), toPlatformLevel(action.actionLevel()),
                toAccessMode(action.accessMode()), action.actionAuth(), action.dataAuth(),
                action.defaultGrantPolicy() == null ? ActionDefaultGrantPolicy.NONE : action.defaultGrantPolicy(),
                action.authInheritActionCode());
    }

    private PlatformActionLevel toPlatformLevel(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel level) {
        if (level == null) {
            return PlatformActionLevel.DEFAULT;
        }
        return switch (level) {
            case LIST -> PlatformActionLevel.LIST;
            case RECORD -> PlatformActionLevel.RECORD;
            case BATCH -> PlatformActionLevel.BATCH;
            case ANY -> PlatformActionLevel.ANY;
        };
    }

    private ActionAccessMode toAccessMode(net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode accessMode) {
        if (accessMode == null) {
            return ActionAccessMode.AUTH_REQUIRED;
        }
        return switch (accessMode) {
            case AUTH_REQUIRED -> ActionAccessMode.AUTH_REQUIRED;
            case LOGIN_REQUIRED -> ActionAccessMode.LOGIN_REQUIRED;
            case ANONYMOUS_ALLOWED -> ActionAccessMode.ANONYMOUS_ALLOWED;
        };
    }

    private <R> R withScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria,
                            Function<Criteria, R> action) {
        return withScope(moduleAlias, policy, criteria, (ignored, scoped) -> action.apply(scoped));
    }

    private <R> R withScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria,
                            java.util.function.BiFunction<DataScopeCriteriaResult, Criteria, R> action) {
        DataScopeCriteriaResult scope = dataScopeCriteriaService.resolveReadScope(moduleAlias, policy,
                criteria == null ? Criteria.of() : criteria, CurrentUserContext.currentUser());
        return withTenantScope(scope, () -> action.apply(scope, scope.criteria()));
    }

    private <R> R withTenantScope(DataScopeCriteriaResult scope, Supplier<R> supplier) {
        if (scope.crossTenant()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("data scope allows cross-tenant read")) {
                return supplier.get();
            }
        }
        return supplier.get();
    }

    private DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return runtime.entityService(moduleAlias, entityAlias);
    }
}
