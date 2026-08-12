package net.ximatai.muyun.spring.common.model.file;

/** Governs the storage implementation behind a stable {@link FileReference} identifier. */
public enum FileReferenceStoragePolicy {
    /** Existing MuYunFileServer upload, confirmation, promotion and deletion lifecycle. */
    MUYUN_FILE_SERVER,
    /** Platform-managed small asset stored inline in the database and migratable without changing the reference id. */
    DATABASE_INLINE
}
