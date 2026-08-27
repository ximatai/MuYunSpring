package net.ximatai.muyun.spring.ability.reference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lifecycle rules for a normal reference, including whether a referrer is
 * preserved, blocks deletion, or is deleted with its target.
 */
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceIntegrity {
    ReferenceTargetUnavailablePolicy onTargetUnavailable() default ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY;

}
