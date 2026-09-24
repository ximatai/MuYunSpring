package net.ximatai.muyun.spring.platform.runtime;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import java.time.Instant;

/** Durable desired runtime revision; not a historical configuration archive or background job. */
@Getter
@Setter
@Table(name = "platform_dynamic_runtime_activation", comment = "Dynamic runtime activation state")
@CompositeIndex(columns = {"module_alias"}, unique = true)
public class DynamicRuntimeActivation extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "desired_revision", type = ColumnType.INT, nullable = false)
    private Integer desiredRevision;
    @Column(name = "active_revision", type = ColumnType.INT)
    private Integer activeRevision;
    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, nullable = false)
    private String status;
    @Column(name = "failure_message", type = ColumnType.TEXT)
    private String failureMessage;
    @Column(name = "attempted_at", type = ColumnType.TIMESTAMP)
    private Instant attemptedAt;
}
