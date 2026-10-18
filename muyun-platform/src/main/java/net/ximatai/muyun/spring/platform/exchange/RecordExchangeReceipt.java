package net.ximatai.muyun.spring.platform.exchange;

import lombok.Getter;
import lombok.Setter;

import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

/**
 * Bounded file baseline and committed-row identity; never a business value archive or access grant.
 */
@Getter
@Setter
@Table(name = "platform_record_exchange_receipt", comment = "Record exchange file and row receipt")
@CompositeIndex(columns = {"expires_at"})
@CompositeIndex(columns = {"tenant_id", "module_alias", "document_id"})
public class RecordExchangeReceipt extends StandardEntity {
    @Column(name = "document_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String documentId;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;

    @Column(name = "purpose", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String purpose;

    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 128)
    private String entityAlias;

    @Column(name = "parent_id", type = ColumnType.VARCHAR, length = 64)
    private String parentId;

    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64)
    private String recordId;

    @Column(name = "record_version", type = ColumnType.INT)
    private Integer recordVersion;

    @Column(name = "record_created_at", type = ColumnType.TIMESTAMP)
    private Instant recordCreatedAt;

    @Column(name = "baseline_json", type = ColumnType.TEXT)
    private String baselineJson;

    @Column(name = "committed_digest", type = ColumnType.VARCHAR, length = 64)
    private String committedDigest;

    @Column(name = "planned_operation", type = ColumnType.VARCHAR, length = 32)
    private String plannedOperation;

    @Column(name = "language_tag", type = ColumnType.VARCHAR, length = 255)
    private String languageTag;

    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false)
    private Instant expiresAt;
}
