package net.ximatai.muyun.spring.platform.attachment;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;

import java.util.Objects;

/**
 * Confirms a temporary FileServer fact before it is bound to a business field.
 * A caller that saves a file reference must promote the file before its business
 * persistence, so a persisted reference never depends on temporary-file cleanup.
 */
final class FileReferenceConfirmationService {
    private final FileTransferClient fileTransferClient;

    FileReferenceConfirmationService(FileTransferClient fileTransferClient) {
        this.fileTransferClient = Objects.requireNonNull(fileTransferClient, "fileTransferClient must not be null");
    }

    FileTransferFileMetadata confirmTemporaryFile(FileReferenceDefinition definition, String fileId) {
        if (definition == null) {
            throw new IllegalArgumentException("file reference definition must not be null");
        }
        FileTransferFileMetadata metadata = fileTransferClient.readMetadata(requireFileId(fileId));
        if (!metadata.temporary()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.file-reference-confirmation.file-reference-must-bind-a-temporary-file",
                    "file reference must bind a temporary file: {value1}"), Map.of("value1", String.valueOf(metadata.fileId())));
        }
        validateMetadata(definition, metadata);
        return metadata;
    }

    /** Promotes a previously confirmed file; the caller observes ambiguous transport outcomes. */
    FileTransferFileMetadata promoteConfirmedFile(FileTransferFileMetadata metadata) {
        FileTransferFileMetadata promoted = fileTransferClient.promote(metadata.fileId());
        if (promoted == null || !metadata.fileId().equals(promoted.fileId()) || promoted.temporary()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.file-reference-confirmation.file-reference-was-not-promoted",
                    "file reference was not promoted: {value1}"), Map.of("value1", String.valueOf(metadata.fileId())));
        }
        return promoted;
    }

    static void validateMetadata(FileReferenceDefinition definition, FileTransferFileMetadata metadata) {
        if (!definition.allowedMediaTypes().isEmpty()
                && !definition.allowedMediaTypes().contains(metadata.mimeType())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.file-reference-confirmation.file-reference-media-type-is-not-allowed",
                    "file reference media type is not allowed: {value1}"), Map.of("value1", String.valueOf(metadata.mimeType())));
        }
        if (definition.maxFileSizeBytes() != null && metadata.sizeBytes() > definition.maxFileSizeBytes()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.file-reference-confirmation.file-reference-exceeds-max-file-size",
                    "file reference exceeds max file size: {value1}"), Map.of("value1", String.valueOf(metadata.fileId())));
        }
    }

    private String requireFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.file-reference-confirmation.file-reference-fileid-must-not-be-blank",
                    "file reference fileId must not be blank"));
        }
        return fileId.trim();
    }
}
