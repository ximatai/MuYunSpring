package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_code_sequence_policy", comment = "Platform code sequence policy")
public class CodeSequencePolicy extends StandardEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Column(name = "start_value", type = ColumnType.BIGINT, comment = "Sequence start value")
    private Long startValue = 1L;

    @Column(name = "step_value", type = ColumnType.BIGINT, comment = "Sequence step value")
    private Long stepValue = 1L;

    @Column(name = "sequence_length", type = ColumnType.INT, comment = "Sequence length")
    private Integer sequenceLength;

    @Column(name = "reset_policy", type = ColumnType.VARCHAR, length = 16, comment = "Reset policy")
    private CodeSequenceResetPolicy resetPolicy = CodeSequenceResetPolicy.NONE;

    @Column(name = "max_value", type = ColumnType.BIGINT, comment = "Max sequence value")
    private Long maxValue;

    @Column(name = "overflow_policy", type = ColumnType.VARCHAR, length = 16, comment = "Overflow policy")
    private CodeSequenceOverflowPolicy overflowPolicy = CodeSequenceOverflowPolicy.ERROR;
}
