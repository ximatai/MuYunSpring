package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

/** Module-owned UI formulas; never executed as record-save formulas. */
@Getter
@Setter
@Table(name = "platform_ui_control_rules", comment = "Form UI control formulas")
@CompositeIndex(columns = {"module_alias"}, unique = true)
public class PlatformUiControlRules extends StandardEnabledSortableEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "rules_json", type = ColumnType.TEXT, nullable = false)
    private String rulesJson;
}
