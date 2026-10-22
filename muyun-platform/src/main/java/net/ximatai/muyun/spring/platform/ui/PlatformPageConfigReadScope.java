package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

/** Page consumption may reference tenant-owned or global configuration; editing remains tenant-scoped. */
final class PlatformPageConfigReadScope {
    private PlatformPageConfigReadScope() {
    }

    static <T extends EntityContract> T findVisible(CrudAbility<T> service, String id) {
        if (id == null || id.isBlank()) return null;
        if (TenantContext.currentTenantId().isPresent()) {
            T local = service.list(Criteria.of().eq("id", id)
                    .eq("tenantId", TenantContext.currentTenantId().orElseThrow()))
                    .stream().findFirst().orElse(null);
            if (local != null) return local;
        }
        try (TenantContext.Scope ignored = TenantContext.system("Read global page configuration reference")) {
            return service.list(Criteria.of().eq("id", id).isNull("tenantId")).stream().findFirst().orElse(null);
        }
    }
}
