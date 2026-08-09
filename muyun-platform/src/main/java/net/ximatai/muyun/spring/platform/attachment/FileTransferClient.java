package net.ximatai.muyun.spring.platform.attachment;

import java.io.InputStream;

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

    /**
     * Opens a trusted, short-lived backend download stream for an already
     * governed file. Callers must close the returned stream.
     *
     * <p>This deliberately exposes content rather than a provider URL or an
     * access token, so business integrations never inherit browser transport
     * credentials.</p>
     */
    default InputStream openContent(String fileId) {
        throw new UnsupportedOperationException("file transfer content access is not configured");
    }

    default void delete(String fileId) {
        throw new UnsupportedOperationException("file transfer delete is not configured");
    }
}
