package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;

import java.util.Objects;

/**
 * Confirms a temporary FileServer fact before it is bound to a business field.
 * A caller that saves a file reference must promote the file before its business
 * persistence, so a persisted reference never depends on temporary-file cleanup.
 */
public final class FileReferenceConfirmationService {
    private final FileTransferClient fileTransferClient;

    public FileReferenceConfirmationService(FileTransferClient fileTransferClient) {
        this.fileTransferClient = Objects.requireNonNull(fileTransferClient, "fileTransferClient must not be null");
    }

    public FileTransferFileMetadata confirmTemporaryFile(FileReferenceDefinition definition, String fileId) {
        if (definition == null) {
            throw new IllegalArgumentException("file reference definition must not be null");
        }
        FileTransferFileMetadata metadata = fileTransferClient.readMetadata(requireFileId(fileId));
        if (!metadata.temporary()) {
            throw new PlatformException("file reference must bind a temporary file: " + metadata.fileId());
        }
        if (!definition.allowedMediaTypes().isEmpty()
                && !definition.allowedMediaTypes().contains(metadata.mimeType())) {
            throw new PlatformException("file reference media type is not allowed: " + metadata.mimeType());
        }
        if (definition.maxFileSizeBytes() != null && metadata.sizeBytes() > definition.maxFileSizeBytes()) {
            throw new PlatformException("file reference exceeds max file size: " + metadata.fileId());
        }
        return metadata;
    }

    /**
     * Confirms the uploaded temporary file and promotes it before the caller
     * persists its business binding. A promotion failure aborts that save path.
     */
    public FileTransferFileMetadata confirmAndPromote(FileReferenceDefinition definition, String fileId) {
        FileTransferFileMetadata metadata = confirmTemporaryFile(definition, fileId);
        FileTransferFileMetadata promoted = fileTransferClient.promote(metadata.fileId());
        if (promoted == null || !metadata.fileId().equals(promoted.fileId()) || promoted.temporary()) {
            throw new PlatformException("file reference was not promoted: " + metadata.fileId());
        }
        return promoted;
    }

    private String requireFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new PlatformException("file reference fileId must not be blank");
        }
        return fileId.trim();
    }
}
