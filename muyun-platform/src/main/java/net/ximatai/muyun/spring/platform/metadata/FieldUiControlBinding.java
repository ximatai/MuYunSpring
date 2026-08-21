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
@Table(name = "platform_field_ui_control_field_mapping", comment = "Platform field UI control field mapping")
@CompositeIndex(columns = {"field_ui_control_alias", "value_key"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "fieldUiControlAlias")
public class FieldUiControlBinding extends StandardSortableEntity {
    @Column(name = "field_ui_control_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Field UI type alias")
    @ChildOf
    @ReferenceTo(target = FieldUiControlService.class)
    private String fieldUiControlAlias;

    @Column(name = "value_key", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Composite value component key")
    private String valueKey;

    @Column(name = "value_field_spec_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Composite value component field spec alias")
    @ReferenceTo(target = FieldSpecService.class)
    private String valueFieldSpecAlias;
}
