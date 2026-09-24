package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Shared metadata lookup, action policy and tenant/data-range boundary for dynamic collaborators. */
final class DynamicRecordAccessContext {
    private final DynamicRecordRuntime runtime;
    private final ActionExecutionPolicyService actionExecutionPolicyService;
    private final DataScopeCriteriaService dataScopeCriteriaService;

    DynamicRecordAccessContext(DynamicRecordRuntime runtime, ActionExecutionPolicyService policy,
                               DataScopeCriteriaService dataScope) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.actionExecutionPolicyService = Objects.requireNonNull(policy, "policy must not be null");
        this.dataScopeCriteriaService = Objects.requireNonNull(dataScope, "dataScope must not be null");
    }

    DynamicModuleDescriptor describe(String moduleAlias) { return runtime.describe(moduleAlias); }

    String mainEntityAlias(String moduleAlias) {
        return runtime.registry().requireModule(moduleAlias).mainEntityAlias();
    }

    boolean hasRegisteredDynamicEntity(String moduleAlias, String entityAlias) {
        return runtime.registry().findModule(moduleAlias)
                .stream()
                .flatMap(module -> module.entities().stream())
                .anyMatch(entity -> entity.alias().equals(entityAlias));
    }

    DynamicEntityDescriptor entityDescriptor(String moduleAlias, String entityAlias) {
        return findEntity(describe(moduleAlias), entityAlias);
    }

    String actionEntityAlias(String moduleAlias, String actionCode) {
        DynamicModuleDescriptor descriptor = describe(moduleAlias);
        findAction(descriptor, actionCode);
        DynamicEntityDescriptor mainEntity = findEntity(descriptor, descriptor.mainEntityAlias());
        if (hasAction(mainEntity, actionCode)) {
            return mainEntity.entityAlias();
        }
        return descriptor.entities().stream()
                .filter(entity -> !entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .filter(entity -> entity.actions().stream().anyMatch(action -> action.code().equals(actionCode)))
                .map(DynamicEntityDescriptor::entityAlias)
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action entity: "
                        + moduleAlias + "." + actionCode));
    }

    DataScopeCriteriaResult requireRecordActionScope(String moduleAlias,
                                                            String entityAlias,
                                                            ActionExecutionPolicy policy,
                                                            Collection<String> recordIds,
                                                            Optional<CurrentUser> currentUser) {
        Set<String> normalized = normalizeRecordIds(recordIds);
        actionExecutionPolicyService.requireRecordAction(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                normalized,
                currentUser
        ));
        return requireActionRecordDataScope(moduleAlias, entityAlias, policy, normalized);
    }

    DynamicActionDescriptor actionDescriptor(String moduleAlias, String actionCode) {
        return findAction(describe(moduleAlias), actionCode);
    }

    DynamicActionDescriptor entityActionDescriptor(String moduleAlias, String entityAlias, String actionCode) {
        return findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
    }

    DataScopeCriteriaResult actionCriteriaScope(String moduleAlias,
                                                String entityAlias,
                                                ActionExecutionPolicy policy,
                                                Criteria criteria,
                                                Collection<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)
                || !policy.requiresDataScope()
                || (criteria == null && !normalizeRecordIds(recordIds).isEmpty())) {
            return null;
        }
        return readScope(moduleAlias, policy, actionExecutionCriteria(criteria, recordIds));
    }

    ActionExecutionPolicy actionPolicy(DynamicActionDescriptor action) {
        return new ActionExecutionPolicy(
                action.code(),
                toPlatformLevel(action.actionLevel()),
                toAccessMode(action.accessMode()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                action.authInheritActionCode()
        );
    }

    Criteria idsCriteria(Collection<String> ids) {
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            return Criteria.of().raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", Map.of()));
        }
        return normalized.size() == 1
                ? Criteria.of().eq("id", normalized.iterator().next())
                : Criteria.of().in("id", List.copyOf(normalized));
    }

    boolean recordVisible(String moduleAlias, String entityAlias, DataScopeCriteriaResult scope, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return !withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, 1))).isEmpty();
    }

    Set<String> visibleRecordIds(String moduleAlias,
                                         String entityAlias,
                                         DataScopeCriteriaResult scope,
                                         Collection<String> ids) {
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> loaded = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, normalized.size()))
                .stream()
                .map(DynamicRecord::getId)
                .filter(normalized::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        normalized.stream()
                .filter(loaded::contains)
                .forEach(ordered::add);
        return ordered;
    }

    <R> R withTenantScope(DataScopeCriteriaResult scope, Supplier<R> supplier) {
        if (scope.crossTenant()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("data scope allows cross-tenant read")) {
                return supplier.get();
            }
        }
        return supplier.get();
    }

    DataScopeCriteriaResult requireBusinessRecordMutation(String moduleAlias,
                                                                  String entityAlias,
                                                                  PlatformAction action,
                                                                  Set<String> recordIds) {
        requireRecordAction(moduleAlias, action, recordIds);
        return requireRecordDataScope(moduleAlias, entityAlias, action, recordIds);
    }

    void requireRecordAction(String moduleAlias, PlatformAction action, Set<String> recordIds) {
        actionExecutionPolicyService.requireRecordAction(ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                recordIds,
                CurrentUserContext.currentUser()
        ));
    }

    ActionAuthorizationResult authorize(String moduleAlias, ActionExecutionPolicy policy, Set<String> recordIds) {
        return actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias, policy, recordIds, CurrentUserContext.currentUser()));
    }

    void authorizeAction(String moduleAlias, ActionExecutionPolicy policy) {
        actionExecutionPolicyService.authorizeAction(moduleAlias, policy, CurrentUserContext.currentUser());
    }

    void requireAction(String moduleAlias, PlatformAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        actionExecutionPolicyService.requireAuthorized(ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                Set.of(),
                CurrentUserContext.currentUser()
        ));
    }

    DataScopeCriteriaResult requireRecordDataScope(String moduleAlias,
                                                           String entityAlias,
                                                           PlatformAction action,
                                                           Set<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("record action requires record ids: " + moduleAlias + "." + action.code());
        }
        ActionExecutionContext context = ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                normalized,
                CurrentUserContext.currentUser()
        );
        if (!context.actionPolicy().requiresDataScope()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        return requireActionRecordDataScope(moduleAlias, entityAlias, context.actionPolicy(), normalized);
    }

    DataScopeCriteriaResult requireActionRecordDataScope(String moduleAlias,
                                                                 String entityAlias,
                                                                 ActionExecutionPolicy policy,
                                                                 Collection<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (!policy.requiresDataScope() || normalized.isEmpty()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Criteria idCriteria = normalized.size() == 1
                ? Criteria.of().eq("id", normalized.iterator().next())
                : Criteria.of().in("id", List.copyOf(normalized));
        DataScopeCriteriaResult scope = readScope(moduleAlias, policy, idCriteria);
        long visible = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, normalized.size()))
                .stream()
                .map(DynamicRecord::getId)
                .filter(normalized::contains)
                .distinct()
                .count());
        if (visible != normalized.size()) {
            throw new PlatformAccessDeniedException("record data permission denied: " + moduleAlias + "." + policy.actionCode(),
                    ErrorScope.module(moduleAlias).action(policy.actionCode()));
        }
        return scope;
    }

    Set<String> normalizeRecordIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .forEach(normalized::add);
        return java.util.Collections.unmodifiableSet(normalized);
    }

    private Criteria actionExecutionCriteria(Criteria criteria, Collection<String> recordIds) {
        Criteria scoped = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            scoped.andGroup(criteria.getRoot());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (normalized.isEmpty()) {
            return scoped;
        }
        if (normalized.size() == 1) {
            return scoped.eq("id", normalized.iterator().next());
        }
        return scoped.in("id", List.copyOf(normalized));
    }

    DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return runtime.entityService(moduleAlias, entityAlias);
    }

    boolean supportsCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        return findEntity(describe(moduleAlias), entityAlias).capabilities().contains(capability.name());
    }

    void requireCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        if (!supportsCapability(moduleAlias, entityAlias, capability)) {
            throw new PlatformException("dynamic entity does not support capability: " + capability);
        }
    }

    private DynamicEntityDescriptor findEntity(DynamicModuleDescriptor descriptor, String entityAlias) {
        return descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(entityAlias))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic entity: "
                        + descriptor.moduleAlias() + "." + entityAlias));
    }

    private DynamicActionDescriptor findAction(DynamicModuleDescriptor module, String actionCode) {
        return module.actions().stream()
                .filter(action -> action.code().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action: "
                        + module.moduleAlias() + "." + actionCode));
    }

    private DynamicActionDescriptor findAction(String moduleAlias, DynamicEntityDescriptor entity, String actionCode) {
        return entity.actions().stream()
                .filter(action -> action.code().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action: "
                        + moduleAlias + "." + entity.entityAlias() + "." + actionCode));
    }

    private boolean hasAction(DynamicEntityDescriptor entity, String actionCode) {
        return entity.actions().stream().anyMatch(action -> action.code().equals(actionCode));
    }

    DataScopeCriteriaResult readScope(String moduleAlias, PlatformAction action, Criteria criteria) {
        return readScope(moduleAlias, action.executionPolicy(), criteria);
    }

    DataScopeCriteriaResult readScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria) {
        return dataScopeCriteriaService.resolveReadScope(moduleAlias, policy,
                criteria == null ? Criteria.of() : criteria,
                CurrentUserContext.currentUser());
    }

    Map<String, DynamicRecord> visibleActionRecords(String moduleAlias, String entityAlias,
                                                     ActionExecutionPolicy policy, Set<String> recordIds) {
        Criteria criteria = idsCriteria(recordIds);
        if (!policy.requiresDataScope() || !supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            Map<String, DynamicRecord> records = actionRecords(moduleAlias, entityAlias,
                    DataScopeCriteriaResult.unrestricted(criteria), recordIds);
            if (records.size() != recordIds.size()) {
                throw new IllegalArgumentException("dynamic record does not exist in requested scope: " + moduleAlias);
            }
            return records;
        }
        try {
            return actionRecords(moduleAlias, entityAlias, readScope(moduleAlias, policy, criteria), recordIds);
        } catch (PlatformException | IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    /** Keep each action's authorised scope and its records together; never reload their union in another tenant. */
    private Map<String, DynamicRecord> actionRecords(String moduleAlias, String entityAlias,
                                                     DataScopeCriteriaResult scope, Set<String> recordIds) {
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, recordIds.size())).stream()
                .filter(record -> recordIds.contains(record.getId()))
                .collect(java.util.stream.Collectors.toMap(DynamicRecord::getId, java.util.function.Function.identity())));
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
}
