package net.ximatai.muyun.spring.common.model.file;

/**
 * A stable file fact supplied by MuYunFileServer when a temporary file becomes
 * a business record reference.
 */
public enum FileReferenceMetadata {
    ORIGINAL_FILENAME,
    EXTENSION,
    MIME_TYPE,
    SIZE_BYTES,
    SHA256
}
