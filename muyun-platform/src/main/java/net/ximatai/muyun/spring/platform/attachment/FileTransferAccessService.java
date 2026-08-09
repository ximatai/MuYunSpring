package net.ximatai.muyun.spring.platform.attachment;

/**
 * Issues short-lived transport credentials for the current authenticated user.
 *
 * <p>Implementations must derive tenant and subject from trusted platform context;
 * callers never supply either value.  Business modules remain responsible for
 * authorising their own record and for persisting only the resulting {@code fileId}.
 * </p>
 */
public interface FileTransferAccessService {
    FileTransferAccess issueUploadAccess();

    FileTransferAccess issueMetadataAccess(String fileId);

    FileTransferAccess issuePromoteAccess(String fileId);

    default FileTransferAccess issueDeleteAccess(String fileId) {
        throw new UnsupportedOperationException("file delete access is not supported");
    }

    FileTransferAccess issuePreviewAccess(String fileId);

    FileTransferAccess issueDownloadAccess(String fileId);
}
