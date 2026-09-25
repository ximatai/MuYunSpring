package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_code_sequence_state", comment = "Platform code sequence state")
@CompositeIndex(columns = {"tenant_id", "rule_id", "basis_key", "period_key"}, unique = true)
public class CodeSequenceState extends StandardEntity {
    public static final String DEFAULT_BUCKET = "__DEFAULT__";

    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Indexed
    @Column(name = "basis_key", type = ColumnType.VARCHAR, length = 512, nullable = false, comment = "Sequence basis key")
    private String basisKey = DEFAULT_BUCKET;

    @Indexed
    @Column(name = "period_key", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Sequence period key")
    private String periodKey = DEFAULT_BUCKET;

    @Column(name = "current_value", type = ColumnType.BIGINT, nullable = false, comment = "Current sequence value")
    private Long currentValue = 0L;
}
