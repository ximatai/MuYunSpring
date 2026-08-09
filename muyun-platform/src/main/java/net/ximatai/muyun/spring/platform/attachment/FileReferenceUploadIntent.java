package net.ximatai.muyun.spring.platform.attachment;

/** The business fact the upload ticket is intended to support. */
public enum FileReferenceUploadIntent {
    CREATE,
    /** Adds a file to an already bound multi-file reference without replacing an existing file. */
    APPEND,
    REPLACE
}
