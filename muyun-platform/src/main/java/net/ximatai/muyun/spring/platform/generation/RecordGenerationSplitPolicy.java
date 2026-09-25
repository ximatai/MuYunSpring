package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Table(name = "platform_record_generation_split_policy", comment = "Record generation split policy")
public class RecordGenerationSplitPolicy extends StandardSortableEntity {
    @Indexed
    @Column(name = "object_mapping_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Generation object mapping id")
    @Required
    private String objectMappingId;

    @Column(name = "quantity_field", type = ColumnType.VARCHAR, length = 64, comment = "Quantity field")
    private String quantityField;

    @Column(name = "quantity_module_metadata_field_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Quantity module metadata field id")
    private String quantityModuleMetadataFieldId;

    @Column(name = "quantity_step", type = ColumnType.INT, comment = "Quantity step")
    private Integer quantityStep;

    private transient List<RecordGenerationSplitGroupField> groupFields = new ArrayList<>();
}
