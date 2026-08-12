package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.file.ManagedFileStorageKind;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Storage-neutral file asset. Inline content is retained only while storageKind is DATABASE_INLINE. */
@Table(name = "platform_managed_file_asset", comment = "Platform managed file asset")
public class ManagedFileAsset extends StandardEntity {
    @Column(name = "storage_kind", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Physical storage kind")
    private ManagedFileStorageKind storageKind;

    @Column(name = "provider_file_id", type = ColumnType.VARCHAR, length = 128, comment = "External provider file id")
    private String providerFileId;

    @Column(name = "content_base64", type = ColumnType.TEXT, comment = "Inline Base64 image data URL")
    private String contentBase64;

    @Column(name = "original_filename", type = ColumnType.VARCHAR, length = 255, comment = "Original file name")
    private String originalFilename;

    @Column(name = "mime_type", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Verified MIME type")
    private String mimeType;

    @Column(name = "size_bytes", type = ColumnType.BIGINT, nullable = false, comment = "Decoded content size")
    private Long sizeBytes;

    @Column(name = "sha256", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Decoded content SHA-256")
    private String sha256;

    public ManagedFileStorageKind getStorageKind() { return storageKind; }
    public void setStorageKind(ManagedFileStorageKind storageKind) { this.storageKind = storageKind; }
    public String getProviderFileId() { return providerFileId; }
    public void setProviderFileId(String providerFileId) { this.providerFileId = providerFileId; }
    public String getContentBase64() { return contentBase64; }
    public void setContentBase64(String contentBase64) { this.contentBase64 = contentBase64; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
