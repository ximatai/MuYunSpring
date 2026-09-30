package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Immutable child-creation identity committed with the metadata, relation and physical schema. */
@Getter
@Setter
@Table(name = "platform_child_metadata_receipt", comment = "Child metadata creation receipt")
public class ModuleChildMetadataCreationReceipt extends StandardEntity {
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String requestDigest;
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String payloadDigest;
    @Column(type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String parentRelationId;
    @Column(type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String metadataId;
    @Column(type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String relationId;
}
