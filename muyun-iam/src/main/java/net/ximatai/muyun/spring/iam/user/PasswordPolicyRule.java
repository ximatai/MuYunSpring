package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

@Getter
@Setter
@Table(name = "iam_password_policy_rule", comment = "Password policy regex rule")
@SortPartitionBy(fields = "scopeKey")
@InitialDataFields(
        managed = {"scopeType", "scopeId", "scopeKey", "pattern", "message", "description"},
        operator = {"title", "enabled", "sortOrder"}
)
@Required(fields = "title")
@NormalizeText(fields = "title")
public class PasswordPolicyRule extends StandardEnabledSortableEntity {
    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Policy scope type", defaultVal = @Default(varchar = "global"))
    private PasswordPolicyScopeType scopeType = PasswordPolicyScopeType.GLOBAL;

    @OptionLoad(source = "scopeType")
    private transient String scopeTypeTitle;

    @Column(name = "scope_id", type = ColumnType.VARCHAR, length = 64, comment = "Policy scope id")
    private String scopeId;

    @Column(name = "scope_key", type = ColumnType.VARCHAR, length = 96, nullable = false,
            comment = "Stable non-null policy scope key", defaultVal = @Default(varchar = "global:"))
    private String scopeKey = "global:";

    @Column(name = "pattern", type = ColumnType.VARCHAR, length = 512, nullable = false,
            comment = "Java regular expression")
    @Required
    @NormalizeText
    private String pattern;

    @Column(name = "message", type = ColumnType.VARCHAR, length = 256, nullable = false,
            comment = "Validation failure message")
    @Required
    @NormalizeText
    private String message;

    @Column(name = "description", type = ColumnType.TEXT, comment = "Rule description")
    private String description;

}
