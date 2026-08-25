package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class DefaultTenantMenuProvisioner implements TenantCreationProvisioner {
    public static final String TENANT_ADMIN_SCHEME_ALIAS = "tenant_admin";
    public static final String TENANT_ADMIN_SCHEME_TITLE = "租户管理";
    private static final String SCHEME_ID_PREFIX = "tenant_menu_";
    private static final String MENU_ID_PREFIX = "tenant_menu_";
    private static final int STANDARD_ID_MAX_LENGTH = 32;
    private static final int HASH_LENGTH = 16;

    private final MenuSchemeService schemeService;
    private final MenuService menuService;

    public DefaultTenantMenuProvisioner(MenuSchemeService schemeService, MenuService menuService) {
        this.schemeService = Objects.requireNonNull(schemeService, "schemeService must not be null");
        this.menuService = Objects.requireNonNull(menuService, "menuService must not be null");
    }

    @Override
    public void afterTenantCreated(String tenantId) {
        reconcileTenantAdminMenus(tenantId);
    }

    /**
     * Reconciles the system-provided default menu copy for an existing tenant.
     * Tenant-specific business menus are outside this method's scope.
     */
    public void reconcileTenantAdminMenus(String tenantId) {
        String validTenantId = requireText(tenantId, "tenantId");
        try (TenantContext.Scope ignored = TenantContext.use(validTenantId)) {
            MenuScheme scheme = ensureTenantAdminScheme(validTenantId);
            copySystemAdminMenus(validTenantId, scheme.getId(), TreeAbility.ROOT_ID);
        }
    }

    public static String tenantAdminSchemeId(String tenantId) {
        return SCHEME_ID_PREFIX + shortHash(requireText(tenantId, "tenantId"));
    }

    private MenuScheme ensureTenantAdminScheme(String tenantId) {
        String schemeId = tenantAdminSchemeId(tenantId);
        MenuScheme existing = schemeService.selectIgnoreSoftDelete(schemeId);
        if (existing != null) {
            validateExistingScheme(existing, tenantId);
            return existing;
        }
        MenuScheme duplicate = findExistingTenantAdminScheme(tenantId);
        if (duplicate != null) {
            return duplicate;
        }
        MenuScheme scheme = new MenuScheme();
        scheme.setId(schemeId);
        scheme.setAlias(TENANT_ADMIN_SCHEME_ALIAS);
        scheme.setScopeType(MenuScopeType.TENANT);
        scheme.setTitle(TENANT_ADMIN_SCHEME_TITLE);
        scheme.setEnabled(Boolean.TRUE);
        scheme.setSortOrder(1);
        schemeService.insert(scheme);
        return scheme;
    }

    private MenuScheme findExistingTenantAdminScheme(String tenantId) {
        return schemeService.list(Criteria.of()
                        .eq(StandardEntitySchema.TENANT_ID_FIELD, tenantId)
                        .eq("scopeType", MenuScopeType.TENANT)
                        .eqNullable("organizationId", null)
                        .eq("alias", TENANT_ADMIN_SCHEME_ALIAS),
                PageRequest.of(1, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void validateExistingScheme(MenuScheme existing, String tenantId) {
        if (!Objects.equals(existing.getTenantId(), tenantId)
                || existing.getScopeType() != MenuScopeType.TENANT
                || existing.getOrganizationId() != null
                || !Objects.equals(existing.getAlias(), TENANT_ADMIN_SCHEME_ALIAS)) {
            throw new PlatformException("Default tenant menu scheme identity drift: " + existing.getId());
        }
    }

    private void copySystemAdminMenus(String tenantId, String targetSchemeId, String sourceParentId) {
        for (Menu source : menuService.children(MenuSchemeService.ADMIN_SCHEME_ID, sourceParentId)) {
            String targetId = tenantMenuId(tenantId, source.getId());
            ensureMenuCopy(source, targetSchemeId, targetId, targetParentId(tenantId, source.getParentId()));
            copySystemAdminMenus(tenantId, targetSchemeId, source.getId());
        }
    }

    private String targetParentId(String tenantId, String sourceParentId) {
        if (TreeAbility.ROOT_ID.equals(sourceParentId)) {
            return TreeAbility.ROOT_ID;
        }
        return tenantMenuId(tenantId, sourceParentId);
    }

    private void ensureMenuCopy(Menu source, String targetSchemeId, String targetId, String targetParentId) {
        Menu existing = menuService.selectIgnoreSoftDelete(targetId);
        if (existing != null) {
            updateExistingMenuCopy(existing, targetSchemeId, targetParentId, source);
            return;
        }
        Menu target = new Menu();
        target.setId(targetId);
        target.setSchemeId(targetSchemeId);
        target.setParentId(targetParentId);
        target.setTitle(source.getTitle());
        target.setOpenMode(source.getOpenMode());
        target.setModuleAlias(source.getModuleAlias());
        target.setRoute(source.getRoute());
        target.setExternalUrl(source.getExternalUrl());
        target.setPageMode(source.getPageMode());
        target.setDefaultUiConfigId(source.getDefaultUiConfigId());
        target.setDefaultQueryTemplateId(source.getDefaultQueryTemplateId());
        target.setEntryParamsJson(source.getEntryParamsJson());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setPlatformManaged(Boolean.TRUE);
        target.setPlatformManagedRevision(sourceRevision(source));
        menuService.insert(target);
    }

    private void updateExistingMenuCopy(Menu existing, String targetSchemeId, String targetParentId, Menu source) {
        if (!Objects.equals(existing.getSchemeId(), targetSchemeId)) {
            throw new PlatformException("Default tenant menu identity drift: " + existing.getId());
        }
        if (Boolean.FALSE.equals(existing.getPlatformManaged())) {
            return;
        }
        // Deterministic legacy copies are adopted once. An operator may subsequently set this
        // flag to false to take the menu over without startup reconciliation overwriting it.
        String revision = sourceRevision(source);
        if (sameManagedCopy(existing, targetParentId, source, revision)) {
            return;
        }
        existing.setPlatformManaged(Boolean.TRUE);
        existing.setParentId(targetParentId);
        existing.setTitle(source.getTitle());
        existing.setOpenMode(source.getOpenMode());
        existing.setModuleAlias(source.getModuleAlias());
        existing.setRoute(source.getRoute());
        existing.setExternalUrl(source.getExternalUrl());
        existing.setPageMode(source.getPageMode());
        existing.setDefaultUiConfigId(source.getDefaultUiConfigId());
        existing.setDefaultQueryTemplateId(source.getDefaultQueryTemplateId());
        existing.setEntryParamsJson(source.getEntryParamsJson());
        existing.setEnabled(source.getEnabled());
        existing.setSortOrder(source.getSortOrder());
        existing.setPlatformManagedRevision(revision);
        menuService.update(existing);
    }

    private static boolean sameManagedCopy(Menu existing, String targetParentId, Menu source, String revision) {
        return Boolean.TRUE.equals(existing.getPlatformManaged())
                && Objects.equals(existing.getParentId(), targetParentId)
                && Objects.equals(existing.getTitle(), source.getTitle())
                && Objects.equals(existing.getOpenMode(), source.getOpenMode())
                && Objects.equals(existing.getModuleAlias(), source.getModuleAlias())
                && Objects.equals(existing.getRoute(), source.getRoute())
                && Objects.equals(existing.getExternalUrl(), source.getExternalUrl())
                && Objects.equals(existing.getPageMode(), source.getPageMode())
                && Objects.equals(existing.getDefaultUiConfigId(), source.getDefaultUiConfigId())
                && Objects.equals(existing.getDefaultQueryTemplateId(), source.getDefaultQueryTemplateId())
                && Objects.equals(existing.getEntryParamsJson(), source.getEntryParamsJson())
                && Objects.equals(existing.getEnabled(), source.getEnabled())
                && Objects.equals(existing.getSortOrder(), source.getSortOrder())
                && Objects.equals(existing.getPlatformManagedRevision(), revision);
    }

    private static String sourceRevision(Menu source) {
        return shortHash(String.join("|", String.valueOf(source.getParentId()), String.valueOf(source.getTitle()),
                String.valueOf(source.getOpenMode()), String.valueOf(source.getModuleAlias()), String.valueOf(source.getRoute()),
                String.valueOf(source.getExternalUrl()), String.valueOf(source.getPageMode()),
                String.valueOf(source.getDefaultUiConfigId()), String.valueOf(source.getDefaultQueryTemplateId()),
                String.valueOf(source.getEntryParamsJson()), String.valueOf(source.getEnabled()),
                String.valueOf(source.getSortOrder())));
    }

    private static String tenantMenuId(String tenantId, String sourceMenuId) {
        String validTenantId = requireText(tenantId, "tenantId");
        String validSourceMenuId = requireText(sourceMenuId, "sourceMenuId");
        String candidate = MENU_ID_PREFIX + validTenantId + "_" + shortHash(validSourceMenuId);
        if (candidate.length() <= STANDARD_ID_MAX_LENGTH) {
            return candidate;
        }
        return MENU_ID_PREFIX + shortHash(validTenantId + ":" + validSourceMenuId);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
