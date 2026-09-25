package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_code_issue_log", comment = "Platform code issue log")
public class CodeIssueLog extends StandardEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Indexed
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    @Required
    private String moduleAlias;

    @Indexed
    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Entity alias")
    @Required
    private String entityAlias;

    @Indexed
    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Code field name")
    @Required
    private String fieldName;

    @Indexed
    @Column(name = "basis_key", type = ColumnType.VARCHAR, length = 512, nullable = false, comment = "Sequence basis key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String basisKey = CodeSequenceState.DEFAULT_BUCKET;

    @Indexed
    @Column(name = "period_key", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Sequence period key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String periodKey = CodeSequenceState.DEFAULT_BUCKET;

    @Column(name = "generated_value", type = ColumnType.VARCHAR, length = 256, comment = "Generated code value")
    private String generatedValue;

    @Indexed
    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Issue status")
    private CodeIssueLogStatus status = CodeIssueLogStatus.SUCCESS;

    @Column(name = "retry_count", type = ColumnType.INT, nullable = false, comment = "Duplicate retry count",
            defaultVal = @Default(number = 0))
    private Integer retryCount = 0;

    @Column(name = "message", type = ColumnType.TEXT, comment = "Issue message")
    private String message;
}
