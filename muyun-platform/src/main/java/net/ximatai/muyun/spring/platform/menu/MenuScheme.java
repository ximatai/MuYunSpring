package net.ximatai.muyun.spring.platform.menu;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

@Getter
@Setter
@Table(name = "platform_menu_scheme", comment = "Platform menu scheme")
@InitialDataFields(
        identity = {"alias", "scopeType", "scopeId", "tenantId"},
        operator = {"title", "enabled", "sortOrder"}
)
public class MenuScheme extends StandardEnabledSortableEntity {
    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Menu scheme alias")
    private String alias;

    @Column(name = "scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Menu scheme scope type", defaultVal = @Default(varchar = "tenant"))
    @OptionField(type = OptionSourceType.ENUM, enumType = MenuScopeType.class)
    private MenuScopeType scopeType = MenuScopeType.TENANT;

    @Column(name = "scope_id", type = ColumnType.VARCHAR, length = 64, comment = "Menu scheme scope id")
    private String scopeId;
}
