package net.ximatai.muyun.spring.platform.content;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Append-only source fact. No standalone CRUD or configuration-translation endpoint. */
@Getter @Setter
@Table(name = "platform_business_content_revision", comment = "Business content source revision")
@CompositeIndex(columns = {"source_key", "revision"}, unique = true)
@CompositeIndex(columns = {"tenant_id", "module_alias", "record_id"})
public class BusinessContentRevision extends StandardEntity {
    @Column(name = "source_key", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String sourceKey;
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "entity_key", type = ColumnType.VARCHAR, length = 255, nullable = false)
    private String entityKey;
    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String recordId;
    @Column(name = "field_key", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String fieldKey;
    @Column(name = "revision", type = ColumnType.INT, nullable = false)
    private Integer revision;
    @Column(name = "original_text", type = ColumnType.TEXT)
    private String originalText;
    @Column(name = "original_author_id", type = ColumnType.VARCHAR, length = 64)
    private String originalAuthorId;
    @Column(name = "original_authored_at", type = ColumnType.TIMESTAMP)
    private java.time.Instant originalAuthoredAt;
    @Column(name = "revision_kind", type = ColumnType.VARCHAR, length = 32)
    private String revisionKind;
    @Column(name = "source_language", type = ColumnType.VARCHAR, length = 255)
    private String sourceLanguage;
}
