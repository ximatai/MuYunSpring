package net.ximatai.muyun.spring.platform.application;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Human acceptance of the exact requirements and delivered configuration baseline. */
@Getter
@Setter
@Table(name = "platform_construction_acceptance", comment = "Construction human acceptance")
public class ApplicationConstructionAcceptance extends StandardEntity {
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
    @Column(name = "baseline", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String baseline;
}
