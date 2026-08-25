package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataOptions;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class MenuSchemeService extends AbstractAbilityService<MenuScheme> implements
        SoftDeleteAbility<MenuScheme>,
        EnableAbility<MenuScheme>,
        SortAbility<MenuScheme>,
        InitialDataAbility<MenuScheme>,
        QueryAbility<MenuScheme> {
    public static final String MODULE_ALIAS = "platform.menu_scheme";
    public static final String ADMIN_SCHEME_ID = "platform.menu_scheme.admin";
    public static final String ADMIN_SCHEME_ALIAS = "platform_admin";
    private final Optional<OrganizationHierarchyService> organizationHierarchyService;
    private final SystemMenuSchemeAccessPolicy systemMenuSchemeAccessPolicy;
    private final Supplier<MenuService> menuServiceProvider;

    public MenuSchemeService(BaseDao<MenuScheme, String> schemeDao) {
        this(schemeDao, Optional.empty(), SystemMenuSchemeAccessPolicy.DENY_ALL, null);
    }

    public MenuSchemeService(BaseDao<MenuScheme, String> schemeDao,
                             Optional<OrganizationHierarchyService> organizationHierarchyService) {
        this(schemeDao, organizationHierarchyService, SystemMenuSchemeAccessPolicy.DENY_ALL, null);
    }

    @Autowired
    public MenuSchemeService(BaseDao<MenuScheme, String> schemeDao,
                             Optional<OrganizationHierarchyService> organizationHierarchyService,
                             Optional<SystemMenuSchemeAccessPolicy> systemMenuSchemeAccessPolicy,
                             ObjectProvider<MenuService> menuServiceProvider) {
        this(schemeDao, organizationHierarchyService,
                systemMenuSchemeAccessPolicy.orElse(SystemMenuSchemeAccessPolicy.DENY_ALL),
                menuServiceProvider == null ? null : menuServiceProvider::getIfAvailable);
    }

    public MenuSchemeService(BaseDao<MenuScheme, String> schemeDao,
                             Optional<OrganizationHierarchyService> organizationHierarchyService,
                             SystemMenuSchemeAccessPolicy systemMenuSchemeAccessPolicy) {
        this(schemeDao, organizationHierarchyService, systemMenuSchemeAccessPolicy, null);
    }

    public MenuSchemeService(BaseDao<MenuScheme, String> schemeDao,
                             Optional<OrganizationHierarchyService> organizationHierarchyService,
                             SystemMenuSchemeAccessPolicy systemMenuSchemeAccessPolicy,
                             Supplier<MenuService> menuServiceProvider) {
        super(MODULE_ALIAS, MenuScheme.class, schemeDao);
        this.organizationHierarchyService = organizationHierarchyService == null
                ? Optional.empty()
                : organizationHierarchyService;
        this.systemMenuSchemeAccessPolicy = systemMenuSchemeAccessPolicy == null
                ? SystemMenuSchemeAccessPolicy.DENY_ALL
                : systemMenuSchemeAccessPolicy;
        this.menuServiceProvider = menuServiceProvider;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MenuScheme.class, java.util.List.of("id", "alias", "scopeType", "tenantId", "organizationId", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("alias"));
    }

    @Override
    public void beforeInsert(MenuScheme scheme) {
        normalizeAndValidate(scheme);
    }

    @Override
    public void beforeUpdate(MenuScheme scheme) {
        MenuScheme existing = selectIgnoreSoftDelete(scheme.getId());
        validateImmutableAlias(existing, scheme);
        normalizeAndValidate(scheme);
        rejectTenantOwnershipChangeWhenMenusExist(existing, scheme);
    }

    @Override
    public boolean allowsTenantOwnershipChange(MenuScheme existing, MenuScheme incoming) {
        return TenantContext.isSystem();
    }

    @Override
    public void beforeDelete(String id) {
        rejectDeleteWhenMenusExist(id);
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("platform.admin-menu-scheme", 10);
    }

    @Override
    public List<MenuScheme> initialData() {
        MenuScheme scheme = new MenuScheme();
        scheme.setId(ADMIN_SCHEME_ID);
        scheme.setAlias(ADMIN_SCHEME_ALIAS);
        scheme.setScopeType(MenuScopeType.SYSTEM);
        scheme.setTitle("平台超管");
        scheme.setEnabled(Boolean.TRUE);
        scheme.setSortOrder(1);
        return List.of(scheme);
    }

    @Override
    public net.ximatai.muyun.spring.ability.SortPartition<MenuScheme> sortPartition() {
        return net.ximatai.muyun.spring.ability.SortPartitions.of(scheme -> Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, scheme.getTenantId())
                        .eq("scopeType", scheme.getScopeType())
                        .eqNullable("organizationId", scheme.getOrganizationId()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                        "Menu scheme sort can only move records within the same scope",
                        "tenantId", "scopeType", "organizationId"));
    }

    private void normalizeAndValidate(MenuScheme scheme) {
        scheme.setAlias(requireAlias(scheme.getAlias()));
        if (scheme.getScopeType() == null) {
            scheme.setScopeType(TenantContext.isSystem() ? MenuScopeType.SYSTEM : MenuScopeType.TENANT);
        }
        normalizeScope(scheme);
        rejectDuplicateAlias(scheme);
    }

    private String requireAlias(String alias) {
        return PlatformNameRules.requireIdentifier(alias, "menuSchemeAlias");
    }

    private void normalizeScope(MenuScheme scheme) {
        switch (scheme.getScopeType()) {
            case SYSTEM -> {
                if (!TenantContext.isSystem()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.system-context-required",
                            "System menu scheme requires system context");
                }
                scheme.setTenantId(null);
                scheme.setOrganizationId(null);
            }
            case TENANT -> {
                if (scheme.getTenantId() == null || scheme.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.tenant-required",
                            "Tenant menu scheme requires tenantId");
                }
                scheme.setOrganizationId(null);
            }
            case ORGANIZATION -> {
                if (scheme.getTenantId() == null || scheme.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.organization-tenant-required",
                            "Organization menu scheme requires tenantId");
                }
                if (scheme.getOrganizationId() == null || scheme.getOrganizationId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.organization-required",
                            "Organization menu scheme requires organizationId");
                }
            }
        }
    }

    private void rejectDuplicateAlias(MenuScheme scheme) {
        rejectDuplicate(scheme, Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, scheme.getTenantId())
                        .eq("scopeType", scheme.getScopeType())
                        .eqNullable("organizationId", scheme.getOrganizationId())
                        .eq("alias", scheme.getAlias()),
                "menuSchemeAlias must be unique within scope: " + scheme.getAlias());
    }

    private void rejectDeleteWhenMenusExist(String schemeId) {
        if (menusExist(schemeId)) {
            throw BusinessExceptions.warning("platform.menu-scheme.delete-with-menus-denied",
                    "Menu scheme cannot be deleted while menus exist: " + schemeId);
        }
    }

    /**
     * A menu inherits its tenant ownership from the scheme when it is created. Moving a populated
     * scheme to another tenant would therefore make the already persisted menu tree inconsistent.
     * Scope refinements inside one tenant remain valid: tenant-to-organization and organization
     * changes do not alter menu ownership.
     */
    private void rejectTenantOwnershipChangeWhenMenusExist(MenuScheme existing, MenuScheme incoming) {
        if (existing == null || Objects.equals(existing.getTenantId(), incoming.getTenantId())
                || !menusExist(existing.getId())) {
            return;
        }
        throw BusinessExceptions.warning("platform.menu-scheme.tenant-change-with-menus-denied",
                "Menu scheme tenant cannot be changed while menus exist: " + existing.getId());
    }

    private boolean menusExist(String schemeId) {
        if (schemeId == null || schemeId.isBlank() || menuServiceProvider == null) {
            return false;
        }
        MenuService menuService = menuServiceProvider.get();
        if (menuService == null) {
            return false;
        }
        return menuService.count(Criteria.of().eq("schemeId", schemeId)) > 0;
    }

    /** Alias is the stable external identity; scope remains a validated business-owned setting. */
    private void validateImmutableAlias(MenuScheme existing, MenuScheme scheme) {
        if (existing == null) {
            return;
        }
        if (!Objects.equals(existing.getAlias(), scheme.getAlias())) {
            throw BusinessExceptions.warning("platform.menu-scheme.alias-immutable",
                    "Menu scheme alias cannot be changed");
        }
    }

    public MenuScheme resolveCurrentUserScheme(CurrentUser user) {
        if (user == null) {
            throw new AuthenticationRequiredException("current user is required");
        }
        if (user.system()) {
            return requireFirstEnabledScheme(MenuScopeType.SYSTEM, null, null);
        }
        if (systemMenuSchemeAccessPolicy.canUseSystemMenuScheme(user)) {
            return requireFirstEnabledSystemScheme();
        }
        if (user.tenantId() == null || user.tenantId().isBlank()) {
            throw new PlatformException("current user tenant is required");
        }
        if (user.organizationId() != null && !user.organizationId().isBlank()) {
            MenuScheme organizationScheme = firstOrganizationScheme(user.tenantId(), user.organizationId());
            if (organizationScheme != null) {
                return organizationScheme;
            }
        }
        return requireFirstEnabledScheme(MenuScopeType.TENANT, user.tenantId(), null);
    }

    private MenuScheme firstOrganizationScheme(String tenantId, String organizationId) {
        for (String candidateId : organizationCandidateIds(organizationId)) {
            MenuScheme scheme = firstEnabledScheme(MenuScopeType.ORGANIZATION, tenantId, candidateId);
            if (scheme != null) {
                return scheme;
            }
        }
        return null;
    }

    private List<String> organizationCandidateIds(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return List.of();
        }
        return organizationHierarchyService
                .map(service -> service.organizationIdsFromSelfToRoot(organizationId))
                .filter(ids -> ids != null && !ids.isEmpty())
                .orElseGet(() -> List.of(organizationId));
    }

    private MenuScheme requireFirstEnabledScheme(MenuScopeType scopeType, String tenantId, String organizationId) {
        MenuScheme scheme = firstEnabledScheme(scopeType, tenantId, organizationId);
        if (scheme == null) {
            throw new PlatformConfigurationException("menu scheme is not configured for current user");
        }
        return scheme;
    }

    private MenuScheme requireFirstEnabledSystemScheme() {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve system default menu scheme")) {
            return requireFirstEnabledScheme(MenuScopeType.SYSTEM, null, null);
        }
    }

    private MenuScheme firstEnabledScheme(MenuScopeType scopeType, String tenantId, String organizationId) {
        List<MenuScheme> schemes = list(Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, tenantId)
                        .eq("scopeType", scopeType)
                        .eqNullable("organizationId", organizationId)
                        .eq("enabled", Boolean.TRUE),
                PageRequest.of(1, 1),
                Sort.asc("sortOrder"));
        return schemes.isEmpty() ? null : schemes.getFirst();
    }
}
