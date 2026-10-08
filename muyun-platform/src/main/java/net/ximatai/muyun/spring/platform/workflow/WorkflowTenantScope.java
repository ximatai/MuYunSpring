package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.Objects;

/** Raw workflow repositories retain the caller's tenant partition, including system-owned records. */
final class WorkflowTenantScope {
    private WorkflowTenantScope() {}

    static Criteria criteria() {
        var criteria = Criteria.of();
        if (TenantContext.tenantFilterBypassed()) return criteria;
        return TenantContext.currentTenantId().map(tenant -> criteria.eq("tenantId", tenant))
                .orElseGet(() -> criteria.isNull("tenantId"));
    }

    static <T extends EntityContract> T visible(T record) {
        if (record == null || TenantContext.tenantFilterBypassed()) return record;
        return Objects.equals(TenantContext.currentTenantId().orElse(null), record.getTenantId()) ? record : null;
    }
}
