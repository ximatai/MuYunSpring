package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

@Getter
@Setter
@Table(name = "platform_field_ui_control", comment = "Platform field UI control")
@CompositeIndex(columns = {"alias"}, unique = true)
@InitialDataFields(
        identity = "alias",
        managed = {"defaultFieldSpecAlias", "valueShape", "primaryValueKey", "queryMode", "rendererType", "icon"},
        operator = {"title", "enabled", "sortOrder"}
)
public class FieldUiControl extends StandardEnabledSortableEntity {
    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Field UI type alias")
    private String alias;

    @Column(name = "default_field_spec_alias", type = ColumnType.VARCHAR, length = 64, comment = "Default field type alias")
    @ReferenceTo(target = FieldSpecService.class)
    private String defaultFieldSpecAlias;

    @Column(name = "value_shape", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "Control value shape")
    private FieldUiControlValueShape valueShape;

    @Column(name = "primary_value_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Primary composite value component key")
    private String primaryValueKey;

    @Column(name = "query_mode", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "Query value interpretation")
    private FieldUiControlQueryMode queryMode;

    @Column(name = "renderer_type", type = ColumnType.VARCHAR, length = 32, comment = "Built-in UI adapter renderer type")
    private ViewControlType rendererType;

    @Column(name = "icon", type = ColumnType.VARCHAR, length = 128, comment = "Icon")
    private String icon;
}
