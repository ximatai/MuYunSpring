package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

@Getter
@Setter
@Table(name = "platform_record_generation_field_mapping", comment = "Record generation field mapping")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "objectMappingId")
public class RecordGenerationFieldMapping extends StandardSortableEntity {
    @Indexed
    @Column(name = "object_mapping_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Object mapping id")
    @Required
    private String objectMappingId;

    @Column(name = "source_field", type = ColumnType.VARCHAR, length = 64, comment = "Source field")
    private String sourceField;

    @Column(name = "source_module_metadata_field_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Source module metadata field id")
    private String sourceModuleMetadataFieldId;

    @Column(name = "target_field", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target field")
    private String targetField;

    @Column(name = "target_module_metadata_field_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Target module metadata field id")
    private String targetModuleMetadataFieldId;

    @Column(name = "mapping_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Field mapping type")
    private RecordGenerationFieldSourceType mappingType;

    @Column(name = "constant_value", type = ColumnType.TEXT, comment = "Constant value")
    private String constantValue;

    @Column(name = "formula_expr", type = ColumnType.TEXT, comment = "Formula expression")
    private String formulaExpr;

    @Column(name = "default_value", type = ColumnType.TEXT, comment = "Default value")
    private String defaultValue;
}
