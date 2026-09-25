package net.ximatai.muyun.spring.platform.attachment;

import java.time.Instant;

/**
 * Storage-neutral file facts read from FileServer or a managed inline asset.
 *
 * <p>This is a transport snapshot, not a platform file asset.  The physical storage owner
 * supplies authoritative facts and applications persist only the
 * business facts they need (normally the {@code fileId}).</p>
 */
public record FileTransferFileMetadata(
        String fileId,
        String originalFilename,
        String extension,
        String mimeType,
        long sizeBytes,
        String sha256,
        String status,
        boolean temporary,
        Instant uploadedAt,
        Integer imageWidth,
        Integer imageHeight
) {
    public FileTransferFileMetadata {
        if ((imageWidth == null) != (imageHeight == null)
                || (imageWidth != null && (imageWidth <= 0 || imageHeight <= 0))) {
            throw new IllegalArgumentException("image dimensions must both be absent or positive");
        }
    }
}
