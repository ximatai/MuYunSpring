package net.ximatai.muyun.spring.iam.tenant;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;

/** A tenant's product-level application entitlement. */
@Table(name = "iam_tenant_application", comment = "Tenant application entitlement")
public class TenantApplication extends StandardTitledEntity {
    /**
     * This entitlement cannot outlive its tenant. The explicit field replaces the inherited
     * tenant scope property so the model also declares its lifecycle dependency.
     */
    @ReferenceTo(target = TenantService.class, tenantScope = ReferenceTenantScope.GLOBAL,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
    @ChildOf
    @Column(name = "tenant_id", type = ColumnType.VARCHAR, length = 64, comment = "Tenant id")
    private String tenantId;

    @Getter
    @Setter
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Application alias")
    private String applicationAlias;

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
