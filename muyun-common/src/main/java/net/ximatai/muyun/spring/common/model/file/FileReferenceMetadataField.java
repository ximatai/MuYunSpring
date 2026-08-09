package net.ximatai.muyun.spring.common.model.file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds one FileServer metadata fact to an explicitly declared model field. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface FileReferenceMetadataField {
    FileReferenceMetadata value();

    String field();
}
