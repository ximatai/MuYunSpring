package net.ximatai.muyun.spring.platform.localization;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_configuration_translation", comment = "Published configuration text translations")
@CompositeIndex(columns = {"source_hash", "source_version", "language_tag"}, unique = true)
public class ConfigurationTranslation extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "source_id", type = ColumnType.TEXT, nullable = false)
    private String sourceId;
    @Column(name = "source_hash", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String sourceHash;
    @Column(name = "source_version", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String sourceVersion;
    @Column(name = "language_tag", type = ColumnType.VARCHAR, length = 255, nullable = false)
    private String languageTag;
    @Column(name = "original_text", type = ColumnType.TEXT, nullable = false)
    private String originalText;
    @Column(name = "draft_text", type = ColumnType.TEXT, nullable = false)
    private String draftText;
    @Column(name = "published_text", type = ColumnType.TEXT)
    private String publishedText;
    @Column(name = "published_revision", type = ColumnType.INT, nullable = false)
    private Integer publishedRevision = 0;
}
