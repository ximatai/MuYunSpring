package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Table(name = "platform_code_rule_segment", comment = "Platform code rule segment")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "ruleId")
public class CodeRuleSegment extends StandardSortableEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Column(name = "segment_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Segment type")
    @Required
    private CodeSegmentType segmentType;

    @Column(name = "source_ref", type = ColumnType.VARCHAR, length = 128, comment = "Source reference")
    private String sourceRef;

    @Column(name = "fixed_value", type = ColumnType.VARCHAR, length = 256, comment = "Fixed value")
    private String fixedValue;

    @Column(name = "formula_expr", type = ColumnType.TEXT, comment = "Formula expression")
    private String formulaExpr;

    @Column(name = "date_format", type = ColumnType.VARCHAR, length = 32, comment = "Date format")
    private CodeDateFormat dateFormat;

    @Column(name = "segment_length", type = ColumnType.INT, comment = "Segment length")
    private Integer length;

    @Column(name = "pad_mode", type = ColumnType.VARCHAR, length = 16, comment = "Pad mode",
            defaultVal = @Default(varchar = "NONE"))
    private CodePadMode padMode = CodePadMode.NONE;

    @Column(name = "pad_char", type = ColumnType.VARCHAR, length = 8, comment = "Pad char")
    private String padChar;

    @Column(name = "separator", type = ColumnType.VARCHAR, length = 16, comment = "Segment separator")
    private String separator;

    @Column(name = "sequence_basis", type = ColumnType.BOOLEAN, comment = "Whether segment participates in sequence basis",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean sequenceBasis = Boolean.FALSE;

    @Column(name = "null_policy", type = ColumnType.VARCHAR, length = 32, comment = "Null policy",
            defaultVal = @Default(varchar = "ERROR"))
    private CodeNullPolicy nullPolicy = CodeNullPolicy.ERROR;

    @Column(name = "truncate_policy", type = ColumnType.VARCHAR, length = 16, comment = "Truncate policy",
            defaultVal = @Default(varchar = "NONE"))
    private CodeTruncatePolicy truncatePolicy = CodeTruncatePolicy.NONE;

    private transient List<CodeValueMapping> mappings = new ArrayList<>();
}
