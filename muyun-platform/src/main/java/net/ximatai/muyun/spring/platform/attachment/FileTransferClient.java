package net.ximatai.muyun.spring.platform.attachment;

/**
 * Trusted backend client for the configured file-transfer provider.
 *
 * <p>It centralises provider response parsing and transport failures.  It does
 * not create a platform-side file asset or hide cross-service transaction
 * boundaries: callers must still decide when their own business record is
 * persisted and how a later compensation is handled.</p>
 */
public interface FileTransferClient {
    FileTransferFileMetadata readMetadata(String fileId);

    FileTransferFileMetadata promote(String fileId);

    default void delete(String fileId) {
        throw new UnsupportedOperationException("file transfer delete is not configured");
    }
}
