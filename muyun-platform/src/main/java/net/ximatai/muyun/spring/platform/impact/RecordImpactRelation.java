package net.ximatai.muyun.spring.platform.impact;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_record_impact_relation", comment = "Record impact relation")
@CompositeIndex(columns = {"tenant_id", "source_module_alias", "source_record_id"})
@CompositeIndex(columns = {"tenant_id", "target_module_alias", "target_record_id"})
public class RecordImpactRelation extends StandardEntity {
    @Column(name = "impact_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Impact type")
    @Required
    private RecordImpactType impactType;

    @Column(name = "source_module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Source module alias")
    @Required
    @NormalizeText
    private String sourceModuleAlias;

    @Column(name = "source_record_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Source record id")
    @Required
    @NormalizeText
    private String sourceRecordId;

    @Column(name = "target_module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Target module alias")
    @Required
    @NormalizeText
    private String targetModuleAlias;

    @Column(name = "target_record_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target record id")
    @Required
    @NormalizeText
    private String targetRecordId;

    @Column(name = "generation_rule_id", type = ColumnType.VARCHAR, length = 64, comment = "Generation rule id")
    private String generationRuleId;

    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, comment = "Action code")
    private String actionCode;

    @Column(name = "batch_id", type = ColumnType.VARCHAR, length = 64, comment = "Generate batch id")
    private String batchId;

    @Column(name = "draft_key", type = ColumnType.VARCHAR, length = 64, comment = "Draft key")
    private String draftKey;

    @Column(name = "operator_user_id", type = ColumnType.VARCHAR, length = 64, comment = "Operator user id")
    private String operatorUserId;
}
