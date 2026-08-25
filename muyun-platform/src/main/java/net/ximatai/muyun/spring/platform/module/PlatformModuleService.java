package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Comparator;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;

@Service
public class PlatformModuleService extends AbstractAbilityService<PlatformModule> implements
        SoftDeleteAbility<PlatformModule>,
        EnableAbility<PlatformModule>,
        TreeAbility<PlatformModule>,
        ReferenceAbility<PlatformModule>,
        PlatformManagedProtectionAbility<PlatformModule>,
        QueryAbility<PlatformModule>,
        ApplicationReferenceContributor {

    public static final String MODULE_ALIAS = "platform.module";

    public PlatformModuleService(BaseDao<PlatformModule, String> moduleDao) {
        super(MODULE_ALIAS, PlatformModule.class, moduleDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformModule.class, java.util.List.of("id", "parentId", "applicationAlias", "moduleKind", "systemManaged", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public String resourceKey() {
        return "module";
    }

    @Override
    public String resourceName() {
        return "模块";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforePrepareInsert(PlatformModule module) {
        normalizeAndValidate(module);
    }

    @Override
    public void beforeInsert(PlatformModule module) {
        normalizeAndValidate(module);
    }

    @Override
    public void beforeUpdate(PlatformModule module) {
        normalizeAndValidate(module);
    }

    @Override
    public List<PlatformModule> children(String parentId) {
        if (TreeAbility.ROOT_ID.equals(parentId)) {
            rejectRootChildrenLookup("rootModules(applicationAlias)");
        }
        return TreeAbility.super.children(parentId);
    }

    public List<PlatformModule> rootModules(String applicationAlias) {
        return children(applicationAlias, TreeAbility.ROOT_ID);
    }

    public List<PlatformModule> children(String applicationAlias, String parentId) {
        return TreeAbility.super.children(applicationScope(PlatformNameRules.requireApplicationAlias(applicationAlias)), parentId);
    }

    public PlatformModule resolveVisibleModule(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (TenantContext.currentTenantId().isPresent()) {
            PlatformModule scoped = select(validAlias);
            if (scoped != null) {
                return scoped;
            }
        }
        return selectGlobalModule(validAlias);
    }

    /**
     * Returns the enabled module catalog visible to the current tenant. Tenant-owned modules take
     * precedence over a global module with the same alias, while system callers only receive the
     * global catalog instead of every tenant's private definitions.
     */
    public List<PlatformModule> listVisibleModules() {
        return listVisibleModules(TenantContext.currentTenantId().orElse(null));
    }

    /**
     * Resolves the catalog for an explicitly captured request tenant. This keeps delivery adapters
     * correct even when they temporarily enter system scope to read platform-managed configuration.
     */
    public List<PlatformModule> listVisibleModules(String tenantId) {
        LinkedHashMap<String, PlatformModule> visible = new LinkedHashMap<>();
        listGlobalEnabledModules().forEach(module -> visible.put(module.getAlias(), module));
        String normalizedTenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        if (normalizedTenantId != null) {
            try (TenantContext.Scope ignored = TenantContext.use(normalizedTenantId)) {
                list(Criteria.of()
                        .eq("enabled", Boolean.TRUE)
                        .eq(StandardEntitySchema.TENANT_ID_FIELD, normalizedTenantId),
                        new PageRequest(0, Integer.MAX_VALUE))
                        .forEach(module -> visible.put(module.getAlias(), module));
            }
        }
        return visible.values().stream()
                .sorted(Comparator.comparing(PlatformModule::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PlatformModule::getApplicationAlias)
                        .thenComparing(PlatformModule::getAlias))
                .toList();
    }

    public List<PlatformModule> listSystemManagedStaticModules() {
        try (TenantContext.Scope ignored = TenantContext.system("select system managed static modules")) {
            return list(Criteria.of()
                    .eq("moduleKind", ModuleKind.STATIC)
                    .eq("systemManaged", Boolean.TRUE)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD),
                    new PageRequest(0, Integer.MAX_VALUE));
        }
    }

    private List<PlatformModule> listGlobalEnabledModules() {
        try (TenantContext.Scope ignored = TenantContext.system("select global visible platform modules")) {
            return list(Criteria.of()
                    .eq("enabled", Boolean.TRUE)
                    .isNull(StandardEntitySchema.TENANT_ID_FIELD), new PageRequest(0, Integer.MAX_VALUE))
                    .stream()
                    .filter(module -> module.getTenantId() == null || module.getTenantId().isBlank())
                    .toList();
        }
    }

    private PlatformModule selectGlobalModule(String moduleAlias) {
        try (TenantContext.Scope ignored = TenantContext.system("select global platform module")) {
            return getDao().query(activeCriteria(Criteria.of()
                            .eq("id", moduleAlias)),
                    new PageRequest(0, 1))
                    .stream()
                    .filter(module -> module.getTenantId() == null || module.getTenantId().isBlank())
                    .findFirst()
                    .orElse(null);
        }
    }

    private void normalizeAndValidate(PlatformModule module) {
        String applicationAlias = requireApplicationAlias(module.getApplicationAlias());
        String moduleAlias = requireModuleAlias(module.getAlias(), applicationAlias);
        module.setApplicationAlias(applicationAlias);
        module.setAlias(moduleAlias);
        if (module.getModuleKind() == null) {
            module.setModuleKind(ModuleKind.STATIC);
        }
        normalizeEntry(module);
        validateParentApplication(module);
    }

    private void normalizeEntry(PlatformModule module) {
        if (module.getEntryType() == null) {
            module.setEntryType(ModuleEntryType.MODULE);
        }
        switch (module.getEntryType()) {
            case MODULE -> {
                module.setEntryRoute(null);
                module.setEntryExternalUrl(null);
            }
            case ROUTE -> {
                module.setEntryRoute(normalizeInternalRoute(module.getEntryRoute()));
                module.setEntryExternalUrl(null);
            }
            case LINK -> {
                module.setEntryRoute(null);
                module.setEntryExternalUrl(requireText(module.getEntryExternalUrl(), "LINK module entry requires externalUrl").trim());
            }
        }
    }

    private String normalizeInternalRoute(String route) {
        String normalized = requireText(route, "ROUTE module entry requires route").trim();
        if (!normalized.startsWith("/") || normalized.startsWith("//") || normalized.contains("://")) {
            throw new PlatformException("ROUTE module entry route must be an internal path: " + normalized);
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }

    private String requireApplicationAlias(String applicationAlias) {
        return PlatformNameRules.requireApplicationAlias(applicationAlias);
    }

    private String requireModuleAlias(String moduleAlias, String applicationAlias) {
        return PlatformNameRules.requireModuleAliasInApplication(moduleAlias, applicationAlias);
    }

    private void validateParentApplication(PlatformModule module) {
        validateTreePlacementInScope(module, applicationScope(module.getApplicationAlias()),
                "Module parent must belong to the same application");
    }

    private Criteria applicationScope(String applicationAlias) {
        return Criteria.of().eq("applicationAlias", applicationAlias);
    }
}
