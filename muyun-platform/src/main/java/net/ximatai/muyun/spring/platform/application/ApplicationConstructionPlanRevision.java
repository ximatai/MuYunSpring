package net.ximatai.muyun.spring.platform.application;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_application_construction_plan_revision", comment = "Application construction requirements")
public class ApplicationConstructionPlanRevision extends StandardEntity {
    @Indexed
    @Column(name = "plan_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Construction plan")
    private String planId;
    @Column(name = "revision_number", type = ColumnType.INT, nullable = false, comment = "Confirmed scope revision")
    private Integer revisionNumber;
    @Column(name = "request_id", type = ColumnType.VARCHAR, length = 80, nullable = false, comment = "Confirmation request identity")
    private String requestId;
    @Column(name = "request_digest", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Confirmation content fingerprint")
    private String requestDigest;
    @Column(name = "content_json", type = ColumnType.TEXT, nullable = false, comment = "Immutable confirmed requirements")
    private String contentJson;
}
