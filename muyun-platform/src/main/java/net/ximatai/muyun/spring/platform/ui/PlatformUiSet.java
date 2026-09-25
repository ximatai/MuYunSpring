package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_ui_set", comment = "Platform low-code UI set")
@CompositeIndex(columns = {"module_alias", "alias"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "moduleAlias")
public class PlatformUiSet extends StandardEnabledSortableEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    private String moduleAlias;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "UI set alias")
    private String alias;

    @Column(name = "set_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "UI set type")
    @Required
    private PlatformUiSetType setType;

    @Column(name = "default_set", type = ColumnType.BOOLEAN, comment = "Default UI set for type",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean defaultSet = Boolean.FALSE;
}
