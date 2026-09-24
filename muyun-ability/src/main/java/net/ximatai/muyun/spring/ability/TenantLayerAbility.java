package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit reads for configuration shared between a tenant and the global layer.
 * Does not change ordinary CRUD scope or grant system mutation privileges.
 * Overlay keys, disabled-record masking and candidate ranking remain domain decisions.
 */
public interface TenantLayerAbility<T extends EntityContract> extends CrudAbility<T> {
    /** No tenant context means no tenant-layer records, including in system context. */
    default List<T> listCurrentTenant(Criteria criteria, Sort... sorts) {
        return TenantContext.currentTenantId()
                .map(tenantId -> list(layerCriteria(criteria).eq(StandardEntitySchema.TENANT_ID_FIELD, tenantId), sorts))
                .orElseGet(List::of);
    }

    /** Global means a null tenant ID; the restriction is applied in the storage query. */
    default List<T> listGlobal(Criteria criteria, Sort... sorts) {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("read global configuration layer")) {
            return list(layerCriteria(criteria).isNull(StandardEntitySchema.TENANT_ID_FIELD), sorts);
        }
    }

    /** Tenant candidates first, then global candidates; each layer retains the requested order. */
    default List<T> listTenantAndGlobal(Criteria criteria, Sort... sorts) {
        List<T> records = new ArrayList<>(listCurrentTenant(criteria, sorts));
        records.addAll(listGlobal(criteria, sorts));
        return List.copyOf(records);
    }

    private Criteria layerCriteria(Criteria criteria) {
        Criteria scoped = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            scoped.andGroup(criteria.getRoot());
        }
        return scoped;
    }
}
