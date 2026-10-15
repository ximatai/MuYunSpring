package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Recognizes only managed default-menu copies using the menu producer's deterministic identity. */
@Service
public class MenuConfiguredTextSeedTargets implements ConfiguredTextSeedTargetProvider {
    private final MenuService menus;
    private final MenuSchemeService schemes;
    public MenuConfiguredTextSeedTargets(MenuService menus, MenuSchemeService schemes) {
        this.menus = menus;
        this.schemes = schemes;
    }

    @Override public List<Target> derivedTargets(String moduleAlias, List<TextReference> originals, String tenantId) {
        if (MenuSchemeService.MODULE_ALIAS.equals(moduleAlias)) return schemeTargets(originals, tenantId);
        if (!MenuService.MODULE_ALIAS.equals(moduleAlias)) return List.of();
        try (var scope = TenantContext.bypassTenantFilter("default menu translation baseline targets")) {
            var criteria = Criteria.of().eq("alias", DefaultTenantMenuProvisioner.TENANT_ADMIN_SCHEME_ALIAS)
                    .eq("scopeType", MenuScopeType.TENANT).eqNullable("organizationId", null);
            if (tenantId != null) criteria.eq("tenantId", tenantId);
            Map<String, String> schemeTenants = new HashMap<>();
            for (var scheme : schemes.list(criteria, new PageRequest(0, Integer.MAX_VALUE)))
                if (scheme.getTenantId() != null) schemeTenants.put(scheme.getId(), scheme.getTenantId());
            var menuCriteria = Criteria.of();
            if (tenantId != null) {
                var ids = new ArrayList<>(schemeTenants.keySet());
                ids.add(MenuSchemeService.ADMIN_SCHEME_ID);
                menuCriteria.in("schemeId", ids);
            }
            var rows = menus.list(menuCriteria, new PageRequest(0, Integer.MAX_VALUE));
            var sourceSet = new HashSet<>(originals);
            var baselines = rows.stream().filter(menu -> menu.getTenantId() == null &&
                    MenuSchemeService.ADMIN_SCHEME_ID.equals(menu.getSchemeId())).toList();
            List<Target> targets = new ArrayList<>();
            for (var copy : rows) {
                if (tenantId != null && !tenantId.equals(copy.getTenantId())) continue;
                if (copy.getTenantId() == null || !Boolean.TRUE.equals(copy.getPlatformManaged()) ||
                        !copy.getTenantId().equals(schemeTenants.get(copy.getSchemeId()))) continue;
                for (var baseline : baselines) {
                    if (!DefaultTenantMenuProvisioner.tenantMenuId(copy.getTenantId(), baseline.getId()).equals(copy.getId())) continue;
                    var source = ConfigurationTextSources.menuTitle(baseline.getId(), baseline.getTitle());
                    if (sourceSet.contains(source)) targets.add(new Target(source,
                            ConfigurationTextSources.menuTitle(copy.getId(), copy.getTitle())));
                }
            }
            return List.copyOf(targets);
        }
    }
    private List<Target> schemeTargets(List<TextReference> originals, String tenantId) {
        var criteria = Criteria.of().eq("alias", DefaultTenantMenuProvisioner.TENANT_ADMIN_SCHEME_ALIAS)
                .eq("scopeType", MenuScopeType.TENANT).eqNullable("organizationId", null);
        if (tenantId != null) criteria.eq("tenantId", tenantId);
        try (var ignored = TenantContext.bypassTenantFilter("default menu scheme translation baseline targets")) {
            var current = new HashSet<>(originals);
            return schemes.list(criteria, new PageRequest(0, Integer.MAX_VALUE)).stream()
                    .filter(DefaultTenantMenuProvisioner::isTenantAdminScheme)
                    .filter(scheme -> tenantId == null || tenantId.equals(scheme.getTenantId()))
                    .map(scheme -> schemes.recordTextSources(scheme).get("title"))
                    .filter(Objects::nonNull).filter(current::contains)
                    .map(target -> new Target(DefaultTenantMenuProvisioner.tenantAdminSchemeTitleBaseline(), target)).toList();
        }
    }

}
