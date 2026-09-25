package net.ximatai.muyun.spring.platform.duplicate;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_record_duplicate_rule", comment = "Record duplicate check rule")
@CompositeIndex(columns = {"module_alias", "action_code"}, unique = true)
public class RecordDuplicateRule extends StandardEntity implements EnabledCapable {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Business module alias")
    @Required
    @NormalizeText
    private String moduleAlias;

    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Duplicate check action code")
    private String actionCode;

    @Column(name = "field_names", type = ColumnType.TEXT, nullable = false,
            comment = "Comma separated field names")
    private String fieldNames;

    @Column(name = "enabled", type = ColumnType.BOOLEAN, comment = "Enabled",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean enabled = Boolean.TRUE;
}
