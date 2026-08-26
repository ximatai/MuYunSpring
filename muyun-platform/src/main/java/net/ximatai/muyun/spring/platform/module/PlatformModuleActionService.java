package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorRegistry;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformModuleActionService extends AbstractAbilityService<PlatformModuleAction> implements
        SoftDeleteAbility<PlatformModuleAction>,
        EnableAbility<PlatformModuleAction>,
        SortAbility<PlatformModuleAction>,
        PlatformManagedProtectionAbility<PlatformModuleAction>,
        QueryAbility<PlatformModuleAction> {
    public static final String MODULE_ALIAS = "platform.module_action";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformModuleService moduleService;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;
    private final DynamicActionExecutorRegistry actionExecutorRegistry;

    public PlatformModuleActionService(BaseDao<PlatformModuleAction, String> actionDao,
                                       PlatformModuleService moduleService) {
        this(actionDao, moduleService, Optional.empty(), Optional.empty());
    }

    public PlatformModuleActionService(BaseDao<PlatformModuleAction, String> actionDao,
                                       PlatformModuleService moduleService,
                                       Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        this(actionDao, moduleService, runtimeRefreshCoordinator, Optional.empty());
    }

    @Autowired
    public PlatformModuleActionService(BaseDao<PlatformModuleAction, String> actionDao,
                                       PlatformModuleService moduleService,
                                       Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                       Optional<DynamicActionExecutorRegistry> actionExecutorRegistry) {
        super(MODULE_ALIAS, PlatformModuleAction.class, actionDao);
        this.moduleService = moduleService;
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
        this.actionExecutorRegistry = actionExecutorRegistry.orElse(null);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformModuleAction.class, java.util.List.of("id", "moduleAlias", "actionCode", "entityAlias", "permissionActionCode", "title", "category", "actionLevel", "accessMode", "actionAuth", "dataAuth", "defaultGrantPolicy", "accessModeOverride", "actionAuthOverride", "dataAuthOverride", "defaultGrantPolicyOverride", "executorType", "executorKey", "sourceType", "sourceId", "bindingType", "bindingId", "bindingAlias", "systemManaged", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public Set<String> editablePlatformManagedFields() {
        return Set.of("accessModeOverride", "actionAuthOverride", "dataAuthOverride", "defaultGrantPolicyOverride");
    }

    /** Platform-managed action declarations expose only their explicit governance overrides. */
    @Override
    public boolean allowsOrdinaryPlatformManagedUpdate() {
        return !editablePlatformManagedFields().isEmpty();
    }

    @Override
    public void beforeInsert(PlatformModuleAction action) {
        normalizeAndValidate(action);
    }

    @Override
    public void beforeUpdate(PlatformModuleAction action) {
        normalizeAndValidate(action);
    }

    @Override
    public void afterChanged(PlatformModuleAction action) {
        PlatformModule module = action == null || action.getModuleAlias() == null
                ? null
                : moduleService.select(action.getModuleAlias());
        if (runtimeRefreshCoordinator != null && module != null && module.getModuleKind() == ModuleKind.DYNAMIC) {
            runtimeRefreshCoordinator.refreshByModuleAction(action);
        }
    }

    public List<PlatformModuleAction> listByModuleAliases(List<String> moduleAliases) {
        if (moduleAliases == null || moduleAliases.isEmpty()) {
            return List.of();
        }
        try (TenantContext.Scope ignored = TenantContext.system("select global module actions")) {
            return list(Criteria.of()
                    .in("moduleAlias", moduleAliases)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD), ALL, Sort.asc("sortOrder"));
        }
    }

    public List<PlatformModuleAction> listSystemManagedActions(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        try (TenantContext.Scope ignored = TenantContext.system("select system managed module actions")) {
            return list(Criteria.of()
                    .eq("moduleAlias", validModuleAlias)
                    .eq("systemManaged", Boolean.TRUE)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD), ALL, Sort.asc("sortOrder"));
        }
    }

    public PlatformModuleAction findByModuleAliasAndActionCode(String moduleAlias, String actionCode) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String validActionCode = PlatformNameRules.requireActionCode(actionCode, "actionCode");
        try (TenantContext.Scope ignored = TenantContext.system("select global module action")) {
            return findOne(Criteria.of()
                    .eq("moduleAlias", validModuleAlias)
                    .eq("actionCode", validActionCode)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD));
        }
    }

    /** Restores the permission policy declared by the action contributor or static module. */
    public void clearPermissionGovernanceOverrides(String moduleAlias, String actionId, Integer version) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModuleAction action = select(actionId);
        if (action == null || !validModuleAlias.equals(action.getModuleAlias())) {
            throw new PlatformException("module action does not belong to module: " + validModuleAlias + "." + actionId);
        }
        if (version == null || !version.equals(action.getVersion())) {
            throw new OptimisticLockException("record version conflict: " + actionId);
        }
        PlatformManagedMutationContext.runAsPlatformManaged(() -> {
            action.setAccessModeOverride(null);
            action.setActionAuthOverride(null);
            action.setDataAuthOverride(null);
            action.setDefaultGrantPolicyOverride(null);
            action.setVersion(version);
            update(action);
        });
    }

    public List<PlatformModuleAction> listBySource(ModuleActionSourceType sourceType, String sourceId) {
        if (sourceType == null || sourceId == null || sourceId.isBlank()) {
            return List.of();
        }
        try (TenantContext.Scope ignored = TenantContext.system("select global contributed module actions")) {
            return list(Criteria.of()
                    .eq("sourceType", sourceType)
                    .eq("sourceId", sourceId)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD), ALL, Sort.asc("sortOrder"));
        }
    }

    public List<PlatformModuleAction> listSystemManagedActionsBySourceType(ModuleActionSourceType sourceType) {
        if (sourceType == null) {
            return List.of();
        }
        try (TenantContext.Scope ignored = TenantContext.system("select global contributed module actions")) {
            return list(Criteria.of()
                    .eq("sourceType", sourceType)
                    .eq("systemManaged", Boolean.TRUE)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD), ALL, Sort.asc("sortOrder"));
        }
    }

    private void normalizeAndValidate(PlatformModuleAction action) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(action.getModuleAlias());
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null) {
            throw new PlatformException("Module action requires existing module: " + moduleAlias);
        }
        action.setModuleAlias(moduleAlias);
        action.setActionCode(PlatformNameRules.requireActionCode(action.getActionCode(), "actionCode"));
        if (action.getEntityAlias() != null && action.getEntityAlias().isBlank()) {
            action.setEntityAlias(null);
        }
        if (action.getEntityAlias() != null) {
            action.setEntityAlias(PlatformNameRules.requireIdentifier(action.getEntityAlias(), "entityAlias"));
        }
        if (action.getPermissionActionCode() != null && action.getPermissionActionCode().isBlank()) {
            action.setPermissionActionCode(null);
        }
        action.setPermissionActionCode(action.getPermissionActionCode() == null
                ? action.getActionCode()
                : PlatformNameRules.requireActionCode(action.getPermissionActionCode(), "permissionActionCode"));
        if (action.getTitle() == null || action.getTitle().isBlank()) {
            action.setTitle(action.getActionCode());
        }
        if (action.getCategory() == null) {
            action.setCategory(EntityActionDefinition.defaultCategory(action.getActionCode()));
        }
        if (action.getActionLevel() == null) {
            action.setActionLevel(EntityActionDefinition.defaultLevel(action.getActionCode(), action.getCategory()));
        }
        if (action.getAccessMode() == null) {
            action.setAccessMode(EntityActionAccessMode.AUTH_REQUIRED);
        }
        if (action.getActionAuth() == null) {
            action.setActionAuth(action.getAccessMode() == EntityActionAccessMode.AUTH_REQUIRED);
        }
        if (action.getDataAuth() == null) {
            action.setDataAuth(false);
        }
        if (action.getDefaultGrantPolicy() == null) {
            action.setDefaultGrantPolicy(net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy.NONE);
        }
        if (action.getAvailableExpression() != null && action.getAvailableExpression().isBlank()) {
            action.setAvailableExpression(null);
        }
        if (action.getUnavailableMessage() != null && action.getUnavailableMessage().isBlank()) {
            action.setUnavailableMessage(null);
        }
        if (action.getExecutorType() == null) {
            action.setExecutorType(EntityActionDefinition.defaultExecutorType(action.getCategory()));
        }
        if (action.getExecutorKey() != null && action.getExecutorKey().isBlank()) {
            action.setExecutorKey(null);
        }
        if (action.getSourceId() != null && action.getSourceId().isBlank()) {
            action.setSourceId(null);
        }
        if (action.getSourceVersionId() != null && action.getSourceVersionId().isBlank()) {
            action.setSourceVersionId(null);
        }
        if (action.getBindingId() != null && action.getBindingId().isBlank()) {
            action.setBindingId(null);
        }
        if (action.getBindingAlias() != null && action.getBindingAlias().isBlank()) {
            action.setBindingAlias(null);
        }
        if (action.getSystemManaged() == null) {
            action.setSystemManaged(false);
        }
        validateManualActionBinding(action, module);
        action.setTenantId(null);
        rejectDuplicate(action, Criteria.of()
                        .eq("moduleAlias", action.getModuleAlias())
                        .eq("actionCode", action.getActionCode())
                        .isNull(StandardEntitySchema.TENANT_ID_FIELD),
                "module action must be unique in module: " + action.getModuleAlias() + "." + action.getActionCode());
    }

    private void validateManualActionBinding(PlatformModuleAction action, PlatformModule module) {
        if (Boolean.TRUE.equals(action.getSystemManaged()) || actionExecutorRegistry == null) {
            return;
        }
        if (module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Manual module actions require a dynamic module: " + action.getModuleAlias());
        }
        if (action.getCategory() != EntityActionCategory.CUSTOM
                || action.getExecutorType() != EntityActionExecutorType.SERVICE) {
            throw new PlatformException("Manual module actions must bind a custom service executor: "
                    + action.getModuleAlias() + "." + action.getActionCode());
        }
        DynamicActionExecutorDefinition definition;
        try {
            definition = actionExecutorRegistry.definition(action.getExecutorKey());
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("Manual module action requires a deployed executor: "
                    + action.getModuleAlias() + "." + action.getActionCode(), exception);
        }
        if (!definition.bindable() || !definition.supports(action.getCategory(), action.getActionLevel())) {
            throw new PlatformException("Executor cannot be bound to module action: "
                    + action.getModuleAlias() + "." + action.getActionCode());
        }
        action.setBindingType(ModuleActionBindingType.DYNAMIC_ACTION_EXECUTOR);
        action.setBindingId(definition.executorKey());
        action.setBindingAlias(definition.executorKey());
    }
}
