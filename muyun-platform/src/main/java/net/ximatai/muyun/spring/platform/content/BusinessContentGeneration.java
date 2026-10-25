package net.ximatai.muyun.spring.platform.content;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import java.time.Instant;

/** Translation topic state and immutable request references. The request contains identities and scope references, never source text or credentials. */
@Getter @Setter
@Table(name = "platform_business_content_generation", comment = "Business content translation generation")
@CompositeIndex(columns = {"request_key"}, unique = true)
@CompositeIndex(columns = {"slot_key", "sequence"}, unique = true)
@CompositeIndex(columns = {"finished_at"})
@CompositeIndex(columns = {"tenant_id", "module_alias", "entity_key", "record_id"})
public class BusinessContentGeneration extends StandardEntity {
    @Column(name = "request_key", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String requestKey;
    @Column(name = "slot_key", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String slotKey;
    @Column(name = "sequence", type = ColumnType.INT, nullable = false)
    private Integer sequence;
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
    @Column(name = "request_json", type = ColumnType.TEXT, nullable = false)
    private String requestJson;
    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String status;
    @Column(name = "run_id", type = ColumnType.VARCHAR, length = 32)
    private String runId;
    @Column(name = "finished_at", type = ColumnType.TIMESTAMP)
    private Instant finishedAt;
    @Column(name = "problem_code", type = ColumnType.VARCHAR, length = 64)
    private String problemCode;
}
