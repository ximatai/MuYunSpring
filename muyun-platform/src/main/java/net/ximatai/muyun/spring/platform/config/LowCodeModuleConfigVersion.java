package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_low_code_module_config_version", comment = "Low code module config version")
@CompositeIndex(columns = {"tenant_id", "module_alias", "version_no"}, unique = true)
public class LowCodeModuleConfigVersion extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Module alias")
    private String moduleAlias;

    @Column(name = "version_no", type = ColumnType.INT, nullable = false, comment = "Version number")
    private Integer versionNo;

    @Column(name = "version_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Version status")
    private LowCodeConfigVersionStatus versionStatus = LowCodeConfigVersionStatus.ARCHIVED;

    @Column(name = "current_version", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Current governance pointer", defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean currentVersion = Boolean.FALSE;

    @Column(name = "package_snapshot_text", type = ColumnType.TEXT, nullable = false,
            comment = "Low code module package snapshot")
    @Required
    private String packageSnapshotText;

    @Column(name = "package_hash", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Package sha-256 hash")
    @Required
    private String packageHash;

    @Column(name = "summary_json", type = ColumnType.TEXT, comment = "Version summary json")
    private String summaryJson;

    @Column(name = "source_version_id", type = ColumnType.VARCHAR, length = 32, comment = "Source version id")
    private String sourceVersionId;

    @Column(name = "archived_by", type = ColumnType.VARCHAR, length = 64, comment = "Archived by")
    private String archivedBy;

    @Column(name = "archived_at", type = ColumnType.TIMESTAMP, comment = "Archived at")
    private Instant archivedAt;

    @Column(name = "remark", type = ColumnType.TEXT, comment = "Archive remark")
    private String remark;
}
