package net.ximatai.muyun.spring.platform.attachment;

/**
 * Module-owned authorization and ownership boundary for one declared {@code @FileReference} field.
 * Upload admission and existing-asset access are intentionally separate decisions.
 */
public interface FileReferenceFieldPolicy {
    boolean supportsField(String moduleAlias, String relationCode, String fieldName);

    /** Authorizes one browser upload before any storage-specific transport is issued. */
    void authorizeUpload(FileReferenceUploadRequest request);

    /** Whether this policy exposes the field's existing assets for preview and download. */
    default boolean readAvailable() {
        return false;
    }

    /** Authorizes a preview or download of an existing field value. Only called when {@link #readAvailable()} is true. */
    default void authorizeRead(FileReferenceReadRequest request) {
        throw new UnsupportedOperationException("file-reference read access is not configured");
    }

    /** Resolves the tenant that owns an inline asset before ordinary CRUD persists its id. */
    default String inlineAssetOwnerTenantId(FileReferenceUploadRequest request) {
        return net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null);
    }

    /** Resolves the tenant that owns an inline asset already referenced by a record. */
    default String inlineAssetOwnerTenantId(FileReferenceReadRequest request) {
        return net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null);
    }
}
