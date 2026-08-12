package net.ximatai.muyun.spring.common.model.file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a storage-neutral platform file identifier or identifier collection on a static model.
 * The default policy preserves the existing MuYunFileServer lifecycle; other policies retain the
 * same reference, constraints and metadata-binding contract while selecting a different storage implementation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FileReference {
    String[] allowedMediaTypes() default {};

    /** A positive byte limit, or the default {@code -1} when the field has no extra limit. */
    long maxFileSizeBytes() default -1L;

    /** Maximum number of files held by this field. Values greater than one require a JSON_SET collection field. */
    int maxFiles() default 1;

    FileReferenceStoragePolicy storagePolicy() default FileReferenceStoragePolicy.MUYUN_FILE_SERVER;
}
