package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_workflow_version", comment = "Workflow version")
@CompositeIndex(columns = {"definition_id", "version_no"}, unique = true)
public class WorkflowVersion extends StandardEntity {
    @Column(name = "definition_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow definition id")
    @Required
    private String definitionId;

    @Column(name = "version_no", type = ColumnType.INT, nullable = false, comment = "Workflow version number")
    private Integer versionNo;

    @Column(name = "publish_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Publish status", defaultVal = @Default(varchar = "draft"))
    private WorkflowPublishStatus publishStatus = WorkflowPublishStatus.DRAFT;

    @Column(name = "snapshot_text", type = ColumnType.TEXT, comment = "Published workflow snapshot")
    private String snapshotText;

    @Column(name = "semantic_json", type = ColumnType.TEXT, comment = "Designer semantic workflow json")
    private String semanticJson;

    @Column(name = "layout_json", type = ColumnType.TEXT, comment = "Designer layout json")
    private String layoutJson;

    @Column(name = "published_by", type = ColumnType.VARCHAR, length = 64, comment = "Published by")
    private String publishedBy;

    @Column(name = "published_at", type = ColumnType.TIMESTAMP, comment = "Published at")
    private Instant publishedAt;
}
