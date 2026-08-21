package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.child.ChildOf;

@Getter
@Setter
@Table(name = "platform_field_ui_control_attribute", comment = "Platform field UI control attribute")
@CompositeIndex(columns = {"field_ui_control_alias", "attribute_alias"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "fieldUiControlAlias")
public class FieldUiControlProperty extends StandardSortableEntity {
    @Column(name = "field_ui_control_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Field UI type alias")
    @ChildOf
    @ReferenceTo(target = FieldUiControlService.class)
    private String fieldUiControlAlias;

    @Column(name = "attribute_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Attribute alias")
    private String attributeAlias;

    @Column(name = "value_field_spec_alias", type = ColumnType.VARCHAR, length = 64, comment = "Value field type alias")
    @ReferenceTo(target = FieldSpecService.class)
    private String valueFieldSpecAlias;

    @Column(name = "default_value", type = ColumnType.VARCHAR, length = 512, comment = "Default value")
    private String defaultValue;
}
