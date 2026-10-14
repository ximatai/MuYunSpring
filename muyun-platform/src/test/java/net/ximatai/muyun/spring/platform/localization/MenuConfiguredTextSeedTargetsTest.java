package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MenuConfiguredTextSeedTargetsTest {
    @Test void recognizesOnlyManagedCopiesWithTheProducerIdentityAndRequestedTenant() {
        var menus = mock(MenuService.class);
        var baseline = new Menu(); baseline.setId("platform.menu.group.identity"); baseline.setTitle("组织与权限");
        baseline.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
        var copy = copy("tenant-a", baseline); var otherTenant = copy("tenant-b", baseline);
        var takenOver = copy("tenant-c", baseline); takenOver.setPlatformManaged(false);
        var manual = copy("tenant-d", baseline); manual.setId("manual-same-title");
        var wrongScheme = copy("tenant-e", baseline); wrongScheme.setSchemeId("manual-scheme");
        when(menus.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(baseline, copy, otherTenant, takenOver, manual, wrongScheme));
        var source = ConfigurationTextSources.menuTitle(baseline.getId(), baseline.getTitle());
        var provider = new MenuConfiguredTextSeedTargets(menus);
        try (var scope = TenantContext.system("test menu baseline targets")) {
            assertThat(provider.derivedTargets("platform.menu", List.of(source), null))
                    .extracting(target -> target.target().sourceId()).containsExactlyInAnyOrder(
                            ConfigurationTextSources.menuTitle(copy.getId(), copy.getTitle()).sourceId(),
                            ConfigurationTextSources.menuTitle(otherTenant.getId(), otherTenant.getTitle()).sourceId());
            assertThat(provider.derivedTargets("platform.menu", List.of(source), "tenant-a"))
                    .singleElement().satisfies(target -> assertThat(target.baseline()).isEqualTo(source));
            assertThat(provider.derivedTargets("platform.dictionary_item", List.of(source), null)).isEmpty();
        }
    }

    private Menu copy(String tenantId, Menu baseline) {
        var menu = new Menu(); menu.setTenantId(tenantId); menu.setTitle(baseline.getTitle()); menu.setPlatformManaged(true);
        menu.setId(DefaultTenantMenuProvisioner.tenantMenuId(tenantId, baseline.getId()));
        menu.setSchemeId(DefaultTenantMenuProvisioner.tenantAdminSchemeId(tenantId)); return menu;
    }
}
