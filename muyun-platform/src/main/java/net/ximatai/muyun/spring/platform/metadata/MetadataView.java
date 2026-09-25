package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;

@Getter
@Setter
@Table(name = "platform_metadata_view", comment = "Metadata view")
@CompositeIndex(columns = {"relation_id", "view_type"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "relationId")
public class MetadataView extends StandardEnabledSortableEntity {
    @Column(name = "relation_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Module metadata relation id")
    private String relationId;

    @Column(name = "view_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "View type")
    @Required
    private EntityViewType viewType;
}
