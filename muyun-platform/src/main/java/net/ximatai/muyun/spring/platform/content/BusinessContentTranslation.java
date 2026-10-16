package net.ximatai.muyun.spring.platform.content;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Append-only translation facts, owned by an exact original revision. No independent CRUD module. */
@Getter @Setter
@Table(name = "platform_business_content_translation", comment = "Business content translation revision")
@CompositeIndex(columns = {"source_key", "source_revision_id", "language_tag", "revision"}, unique = true)
@CompositeIndex(columns = {"tenant_id", "module_alias", "record_id"})
public class BusinessContentTranslation extends StandardEntity {
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
    @Column(name = "source_revision", type = ColumnType.INT, nullable = false)
    private Integer sourceRevision;
    @Column(name = "source_revision_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String sourceRevisionId;
    @Column(name = "language_tag", type = ColumnType.VARCHAR, length = 255, nullable = false)
    private String languageTag;
    @Column(name = "revision", type = ColumnType.INT, nullable = false)
    private Integer revision;
    @Column(name = "kind", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String kind;
    @Column(name = "translated_text", type = ColumnType.TEXT)
    private String translatedText;
    @Column(name = "reviewed_draft_revision", type = ColumnType.INT)
    private Integer reviewedDraftRevision;
    @Column(name = "author_name", type = ColumnType.VARCHAR, length = 255)
    private String authorName;
    @Column(name = "generation_reference", type = ColumnType.VARCHAR, length = 255)
    private String generationReference;
    @Column(name = "provider_request_id", type = ColumnType.VARCHAR, length = 255)
    private String providerRequestId;
    @Column(name = "policy_version", type = ColumnType.VARCHAR, length = 128)
    private String policyVersion;
    @Column(name = "context_version", type = ColumnType.VARCHAR, length = 128)
    private String contextVersion;
}
