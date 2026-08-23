package net.ximatai.muyun.spring.ability.reference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a field of the record being edited to an equality condition on a
 * reference candidate. The binding is executed by the reference resolver,
 * never by a client-provided target query.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceCandidateBinding {
    String sourceField();

    String targetField();

    /** A missing source value means there are no valid candidates. */
    boolean required() default true;
}
