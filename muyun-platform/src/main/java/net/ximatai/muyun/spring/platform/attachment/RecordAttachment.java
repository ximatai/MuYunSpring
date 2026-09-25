package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_record_attachment", comment = "Record attachment relation")
@CompositeIndex(columns = {"tenant_id", "module_alias", "record_id"})
@CompositeIndex(columns = {"tenant_id", "file_id"})
public class RecordAttachment extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Business module alias")
    @Required
    @NormalizeText
    private String moduleAlias;

    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Business record id")
    @Required
    @NormalizeText
    private String recordId;

    @Column(name = "file_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "File server file id")
    @Required
    @NormalizeText
    private String fileId;

    @Column(name = "display_name", type = ColumnType.VARCHAR, length = 255, comment = "Display file name")
    private String displayName;

    @Column(name = "sort_order", type = ColumnType.INT, comment = "Display sort order")
    private Integer sort;

    @Column(name = "remark", type = ColumnType.VARCHAR, length = 512, comment = "Attachment remark")
    private String remark;
}
