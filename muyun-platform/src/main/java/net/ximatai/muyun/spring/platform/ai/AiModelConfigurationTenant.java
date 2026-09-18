package net.ximatai.muyun.spring.platform.ai;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/**
 * Legacy selected-tenant grant retained only as input for the explicit ownership migration.
 * It is not part of the active AI configuration aggregate or its web surface.
 */
@Getter
@Setter
@Table(name = "platform_ai_model_configuration_tenant", comment = "AI model configuration tenant grant")
@CompositeIndex(columns = {"configuration_id", "target_tenant_id"}, unique = true)
public class AiModelConfigurationTenant extends StandardEntity {
    @ChildOf
    @ReferenceTo(target = AiModelConfigurationService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    @Column(name = "configuration_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "AI model configuration id")
    private String configurationId;

    @ReferenceTo(moduleAlias = "iam", entityAlias = "tenant", tenantScope = ReferenceTenantScope.GLOBAL)
    @Column(name = "target_tenant_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Granted tenant id")
    private String targetTenantId;

    /** Read-side companion used by both the inline editor and detail relation browse projection. */
    @ReferenceLoad(source = "targetTenantId", field = "title")
    private transient String targetTenantIdTitle;
}
