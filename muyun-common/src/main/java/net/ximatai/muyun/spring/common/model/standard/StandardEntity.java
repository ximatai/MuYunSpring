package net.ximatai.muyun.spring.common.model.standard;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Id;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.time.Instant;

/**
 * Standard base class for static entities managed by the platform.
 */
@Getter
@Setter
public abstract class StandardEntity implements EntityContract {
    /** Read-only reference labels; never stored or accepted as mutation facts. */
    private transient String createdByTitle;
    private transient String updatedByTitle;

    @Id
    @Column(name = "id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "ID")
    private String id;

    @Column(name = "tenant_id", type = ColumnType.VARCHAR, length = 64, comment = "Tenant id")
    private String tenantId;

    @Column(name = "version", type = ColumnType.INT, comment = "Optimistic lock version",
            defaultVal = @Default(number = 0))
    private Integer version = 0;

    @Column(name = "deleted", type = ColumnType.BOOLEAN, comment = "Soft delete flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean deleted = Boolean.FALSE;

    @Column(name = "deleted_at", type = ColumnType.TIMESTAMP, comment = "Deleted at")
    private Instant deletedAt;

    @Column(name = "deleted_by", type = ColumnType.VARCHAR, length = 64, comment = "Deleted by")
    private String deletedBy;

    @Column(name = "created_by", type = ColumnType.VARCHAR, length = 64, comment = "Created by")
    private String createdBy;

    @Column(name = "created_at", type = ColumnType.TIMESTAMP, comment = "Created at")
    private Instant createdAt;

    @Column(name = "updated_by", type = ColumnType.VARCHAR, length = 64, comment = "Updated by")
    private String updatedBy;

    @Column(name = "updated_at", type = ColumnType.TIMESTAMP, comment = "Updated at")
    private Instant updatedAt;
}
