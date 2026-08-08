package net.ximatai.muyun.spring.common.model.file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a single MuYunFileServer-backed file identifier on a static model.
 * Lifecycle actions are supplied by the platform file-reference capability, not
 * by this persistence annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FileReference {
    String[] allowedMediaTypes() default {};

    /** A positive byte limit, or the default {@code -1} when the field has no extra limit. */
    long maxFileSizeBytes() default -1L;
}
