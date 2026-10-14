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
    public MenuConfiguredTextSeedTargets(MenuService menus) { this.menus = menus; }

    @Override public List<Target> derivedTargets(String moduleAlias, List<TextReference> originals, String tenantId) {
        if (!MenuService.MODULE_ALIAS.equals(moduleAlias)) return List.of();
        try (var scope = TenantContext.bypassTenantFilter("default menu translation baseline targets")) {
            var rows = menus.list(Criteria.of(), new PageRequest(0, Integer.MAX_VALUE));
            var sourceSet = new HashSet<>(originals);
            var baselines = rows.stream().filter(menu -> menu.getTenantId() == null &&
                    MenuSchemeService.ADMIN_SCHEME_ID.equals(menu.getSchemeId())).toList();
            List<Target> targets = new ArrayList<>();
            for (var copy : rows) {
                if (tenantId != null && !tenantId.equals(copy.getTenantId())) continue;
                if (copy.getTenantId() == null || !Boolean.TRUE.equals(copy.getPlatformManaged()) ||
                        !DefaultTenantMenuProvisioner.tenantAdminSchemeId(copy.getTenantId()).equals(copy.getSchemeId())) continue;
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
}
