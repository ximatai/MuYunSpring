package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

@Getter
@Setter
@Table(name = "platform_record_write_back_match_rule", comment = "Record write-back match rule")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "ruleId")
public class RecordWriteBackMatchRule extends StandardSortableEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Rule id")
    @Required
    @NormalizeText
    private String ruleId;

    @Column(name = "source_field", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Source field")
    @Required
    @NormalizeText
    private String sourceField;

    @Column(name = "target_field", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target field")
    @Required
    @NormalizeText
    private String targetField;

    @Column(name = "target_relation_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Target relation code for child row matching")
    private String targetRelationCode;
}
