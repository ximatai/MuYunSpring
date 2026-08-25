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
    public static final String SYSTEM_SCOPE_ID = "system";
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
        return QueryDescriptors.fromModel(MODULE_ALIAS, MenuScheme.class, java.util.List.of("id", "alias", "scopeType", "scopeId", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("alias"));
    }

    @Override
    public void beforeInsert(MenuScheme scheme) {
        normalizeAndValidate(scheme);
    }

    @Override
    public void beforeUpdate(MenuScheme scheme) {
        validateImmutableIdentity(scheme);
        normalizeAndValidate(scheme);
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
        scheme.setScopeId(SYSTEM_SCOPE_ID);
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
                        .eq("scopeId", scheme.getScopeId()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                        "Menu scheme sort can only move records within the same scope",
                        "tenantId", "scopeType", "scopeId"));
    }

    private void normalizeAndValidate(MenuScheme scheme) {
        scheme.setAlias(requireAlias(scheme.getAlias()));
        if (scheme.getScopeType() == null || (TenantContext.isSystem()
                && scheme.getScopeType() == MenuScopeType.TENANT
                && (scheme.getTenantId() == null || scheme.getTenantId().isBlank())
                && (scheme.getScopeId() == null || scheme.getScopeId().isBlank()))) {
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
                scheme.setScopeId(SYSTEM_SCOPE_ID);
            }
            case TENANT -> {
                if (scheme.getTenantId() == null || scheme.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.tenant-required",
                            "Tenant menu scheme requires tenantId");
                }
                if (scheme.getScopeId() == null || scheme.getScopeId().isBlank()) {
                    scheme.setScopeId(scheme.getTenantId());
                }
            }
            case ORGANIZATION -> {
                if (scheme.getTenantId() == null || scheme.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.organization-tenant-required",
                            "Organization menu scheme requires tenantId");
                }
                if (scheme.getScopeId() == null || scheme.getScopeId().isBlank()) {
                    throw BusinessExceptions.warning("platform.menu-scheme.organization-scope-required",
                            "Organization menu scheme requires scopeId");
                }
            }
        }
    }

    private void rejectDuplicateAlias(MenuScheme scheme) {
        rejectDuplicate(scheme, Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, scheme.getTenantId())
                        .eq("scopeType", scheme.getScopeType())
                        .eq("scopeId", scheme.getScopeId())
                        .eq("alias", scheme.getAlias()),
                "menuSchemeAlias must be unique within scope: " + scheme.getAlias());
    }

    private void rejectDeleteWhenMenusExist(String schemeId) {
        if (schemeId == null || schemeId.isBlank() || menuServiceProvider == null) {
            return;
        }
        MenuService menuService = menuServiceProvider.get();
        if (menuService == null) {
            return;
        }
        long menuCount = menuService.count(Criteria.of().eq("schemeId", schemeId));
        if (menuCount > 0) {
            throw BusinessExceptions.warning("platform.menu-scheme.delete-with-menus-denied",
                    "Menu scheme cannot be deleted while menus exist: " + schemeId);
        }
    }

    private void validateImmutableIdentity(MenuScheme scheme) {
        MenuScheme existing = selectIgnoreSoftDelete(scheme.getId());
        if (existing == null) {
            return;
        }
        boolean changed = !Objects.equals(existing.getAlias(), scheme.getAlias())
                || existing.getScopeType() != scheme.getScopeType()
                || !Objects.equals(existing.getScopeId(), effectiveScopeId(scheme))
                || !Objects.equals(existing.getTenantId(), scheme.getTenantId());
        if (changed) {
            throw BusinessExceptions.warning("platform.menu-scheme.identity-immutable",
                    "Menu scheme identity cannot be changed");
        }
    }

    private String effectiveScopeId(MenuScheme scheme) {
        if (scheme.getScopeType() == MenuScopeType.SYSTEM) {
            return SYSTEM_SCOPE_ID;
        }
        if (scheme.getScopeType() == MenuScopeType.TENANT
                && (scheme.getScopeId() == null || scheme.getScopeId().isBlank())) {
            return scheme.getTenantId();
        }
        return scheme.getScopeId();
    }

    public MenuScheme resolveCurrentUserScheme(CurrentUser user) {
        if (user == null) {
            throw new AuthenticationRequiredException("current user is required");
        }
        if (user.system()) {
            return requireFirstEnabledScheme(MenuScopeType.SYSTEM, null, SYSTEM_SCOPE_ID);
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
        return requireFirstEnabledScheme(MenuScopeType.TENANT, user.tenantId(), user.tenantId());
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

    private MenuScheme requireFirstEnabledScheme(MenuScopeType scopeType, String tenantId, String scopeId) {
        MenuScheme scheme = firstEnabledScheme(scopeType, tenantId, scopeId);
        if (scheme == null) {
            throw new PlatformConfigurationException("menu scheme is not configured for current user");
        }
        return scheme;
    }

    private MenuScheme requireFirstEnabledSystemScheme() {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve system default menu scheme")) {
            return requireFirstEnabledScheme(MenuScopeType.SYSTEM, null, SYSTEM_SCOPE_ID);
        }
    }

    private MenuScheme firstEnabledScheme(MenuScopeType scopeType, String tenantId, String scopeId) {
        List<MenuScheme> schemes = list(Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, tenantId)
                        .eq("scopeType", scopeType)
                        .eq("scopeId", scopeId)
                        .eq("enabled", Boolean.TRUE),
                PageRequest.of(1, 1),
                Sort.asc("sortOrder"));
        return schemes.isEmpty() ? null : schemes.getFirst();
    }
}
