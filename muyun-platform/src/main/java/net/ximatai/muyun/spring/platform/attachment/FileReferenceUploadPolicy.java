package net.ximatai.muyun.spring.platform.attachment;

/**
 * Module-owned admission policy for a declared {@code @FileReference} field.
 * Implementations must enforce their own directory, project, state and action
 * authorization rules. The platform never grants an upload ticket by default.
 */
public interface FileReferenceUploadPolicy {
    /** Declares a field as upload-capable without requiring a synthetic browser file. */
    boolean supportsField(String moduleAlias, String relationCode, String fieldName);

    boolean supports(FileReferenceUploadRequest request);

    void authorize(FileReferenceUploadRequest request);
}
