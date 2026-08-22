package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;

import java.time.Instant;

/**
 * Read-only menu payload used by the workbench navigation endpoint.
 *
 * <p>The menu table deliberately stores target fields rather than a second, duplicated entry type.
 * This projection adds the type resolved from the target module so browser clients can safely decide
 * whether the target is a dynamic module, an internal route, or an external link.</p>
 */
public record MenuNavigationView(
        String id,
        String tenantId,
        Integer version,
        Boolean deleted,
        Instant deletedAt,
        String deletedBy,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        String title,
        Integer sortOrder,
        String parentId,
        Boolean enabled,
        String schemeId,
        MenuOpenMode openMode,
        String moduleAlias,
        String route,
        String externalUrl,
        MenuPageMode pageMode,
        String defaultUiConfigId,
        String defaultQueryTemplateId,
        String entryParamsJson,
        ModuleEntryType entryType
) {
    public static MenuNavigationView from(Menu menu, ModuleEntryType entryType) {
        return new MenuNavigationView(
                menu.getId(),
                menu.getTenantId(),
                menu.getVersion(),
                menu.getDeleted(),
                menu.getDeletedAt(),
                menu.getDeletedBy(),
                menu.getCreatedBy(),
                menu.getCreatedAt(),
                menu.getUpdatedBy(),
                menu.getUpdatedAt(),
                menu.getTitle(),
                menu.getSortOrder(),
                menu.getParentId(),
                menu.getEnabled(),
                menu.getSchemeId(),
                menu.getOpenMode(),
                menu.getModuleAlias(),
                menu.getRoute(),
                menu.getExternalUrl(),
                menu.getPageMode(),
                menu.getDefaultUiConfigId(),
                menu.getDefaultQueryTemplateId(),
                menu.getEntryParamsJson(),
                entryType
        );
    }
}
