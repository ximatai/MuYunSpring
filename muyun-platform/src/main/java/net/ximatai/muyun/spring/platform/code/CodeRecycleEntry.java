package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_code_recycle_entry", comment = "Platform code recycle entry")
@CompositeIndex(columns = {"tenant_id", "rule_id", "basis_key", "period_key", "recycled_value"}, unique = true)
public class CodeRecycleEntry extends StandardEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Indexed
    @Column(name = "basis_key", type = ColumnType.VARCHAR, length = 512, nullable = false, comment = "Sequence basis key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String basisKey = CodeSequenceState.DEFAULT_BUCKET;

    @Indexed
    @Column(name = "period_key", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Sequence period key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String periodKey = CodeSequenceState.DEFAULT_BUCKET;

    @Indexed
    @Column(name = "recycled_value", type = ColumnType.VARCHAR, length = 256, nullable = false, comment = "Recycled code value")
    @Required
    private String recycledValue;

    @Indexed
    @Column(name = "source_record_id", type = ColumnType.VARCHAR, length = 32, comment = "Source record id")
    private String sourceRecordId;

    @Indexed
    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Recycle status",
            defaultVal = @Default(varchar = "AVAILABLE"))
    private CodeRecycleStatus status = CodeRecycleStatus.AVAILABLE;
}
