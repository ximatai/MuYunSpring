package net.ximatai.muyun.spring.common.model.file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares this field as a platform-managed snapshot of one file fact from a
 * single-file {@link FileReference} source, regardless of its storage policy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FileReferenceMetadataField {
    String source();

    FileReferenceMetadata value();
}
