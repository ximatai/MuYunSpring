package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Short-lived upload admission and exclusive pending file claim; identity is always server supplied. */
@Table(name = "platform_file_reference_upload", comment = "Pending field upload provenance")
@CompositeIndex(columns = {"file_id"}, unique = true)
@CompositeIndex(columns = {"tenant_id", "expires_at"})
public class FileReferenceUpload extends StandardEntity {
    @Column(name = "file_id", type = ColumnType.VARCHAR, length = 128)
    private String fileId;
    @Column(name = "uploader_user_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String uploaderUserId;
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String moduleAlias;
    @Column(name = "relation_code", type = ColumnType.VARCHAR, length = 128)
    private String relationCode;
    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String fieldName;
    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64)
    private String recordId;
    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false)
    private java.time.Instant expiresAt;
    @Column(name = "ticket_identifier", type = ColumnType.VARCHAR, length = 64)
    private String ticketIdentifier;
    @Column(name = "used_at", type = ColumnType.TIMESTAMP)
    private java.time.Instant usedAt;
    public String getTicketIdentifier() { return ticketIdentifier; }
    public void setTicketIdentifier(String value) { ticketIdentifier = value; }
    public java.time.Instant getUsedAt() { return usedAt; }
    public void setUsedAt(java.time.Instant value) { usedAt = value; }
    public String getFileId() { return fileId; }
    public void setFileId(String value) { fileId = value; }
    public String getUploaderUserId() { return uploaderUserId; }
    public void setUploaderUserId(String value) { uploaderUserId = value; }
    public String getModuleAlias() { return moduleAlias; }
    public void setModuleAlias(String value) { moduleAlias = value; }
    public String getRelationCode() { return relationCode; }
    public void setRelationCode(String value) { relationCode = value; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String value) { fieldName = value; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String value) { recordId = value; }
    public java.time.Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.Instant value) { expiresAt = value; }
}
