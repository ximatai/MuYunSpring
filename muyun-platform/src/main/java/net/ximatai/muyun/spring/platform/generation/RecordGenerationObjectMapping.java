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
@Table(name = "platform_record_generation_object_mapping", comment = "Record generation object mapping")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "ruleId")
public class RecordGenerationObjectMapping extends StandardSortableEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Generation rule id")
    @Required
    private String ruleId;

    @Column(name = "source_object_alias", type = ColumnType.VARCHAR, length = 64,
            comment = "Source object alias")
    private String sourceObjectAlias;

    @Column(name = "source_relation_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Source relation code")
    private String sourceRelationCode;

    @Column(name = "target_object_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target object alias")
    private String targetObjectAlias;

    @Column(name = "target_relation_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Target relation code")
    private String targetRelationCode;

    @Column(name = "split_driver", comment = "Whether this object mapping drives target draft splitting")
    private Boolean splitDriver;

    private transient List<RecordGenerationFieldMapping> fieldMappings = new ArrayList<>();

    private transient RecordGenerationSplitPolicy splitPolicy;
}
