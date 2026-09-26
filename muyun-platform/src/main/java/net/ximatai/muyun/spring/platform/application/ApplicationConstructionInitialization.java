package net.ximatai.muyun.spring.platform.application;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Committed initialization fact; one construction object is initialized at most once. */
@Getter
@Setter
@Table(name = "platform_construction_initialization", comment = "Construction module initialization receipt")
public class ApplicationConstructionInitialization extends StandardEntity {
    @Indexed
    @Column(name = "plan_id", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String planId;
    @Column(name = "plan_revision", type = ColumnType.INT, nullable = false)
    private Integer planRevision;
    @Column(name = "object_key", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String objectKey;
    @Column(name = "request_id", type = ColumnType.VARCHAR, length = 80, nullable = false)
    private String requestId;
    @Column(name = "request_digest", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String requestDigest;
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "metadata_id", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String metadataId;
    @Column(name = "relation_id", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String relationId;
}
