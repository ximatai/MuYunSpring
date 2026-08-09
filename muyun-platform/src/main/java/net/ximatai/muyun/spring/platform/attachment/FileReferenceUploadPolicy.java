package net.ximatai.muyun.spring.platform.attachment;

/**
 * Module-owned admission policy for a declared {@code @FileReference} field.
 * Implementations must enforce their own directory, project, state and action
 * authorization rules. The platform never grants an upload ticket by default.
 */
public interface FileReferenceUploadPolicy {
    /**
     * Declares the file-reference fields owned by this policy. The platform uses this
     * single declaration both to expose the uploader in a descriptor and to resolve
     * the ticket policy; implementations must not duplicate the same match elsewhere.
     */
    boolean supportsField(String moduleAlias, String relationCode, String fieldName);

    /** Authorizes one explicit browser request, or throws a platform access exception. */
    void authorize(FileReferenceUploadRequest request);
}
