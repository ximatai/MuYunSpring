package net.ximatai.muyun.spring.platform.attachment;

/**
 * Operations covered by a short-lived file transfer credential.  They describe
 * transport only; file ownership and business lifecycle remain with the caller.
 */
public enum FileTransferOperation {
    UPLOAD,
    METADATA,
    PROMOTE,
    DELETE,
    PREVIEW,
    DOWNLOAD
}
