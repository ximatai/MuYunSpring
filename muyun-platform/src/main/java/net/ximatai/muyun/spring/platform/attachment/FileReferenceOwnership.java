package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Immutable ownership reservation. The primary key is the FileServer file id, not a binding id. */
@Table(name = "platform_file_reference_ownership", comment = "Exclusive remote file business ownership")
public class FileReferenceOwnership extends StandardEntity {
    @Column(type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String recordId;
    @Column(type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String fieldName;

    public String getModuleAlias() { return moduleAlias; }
    public void setModuleAlias(String value) { moduleAlias = value; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String value) { recordId = value; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String value) { fieldName = value; }
}
