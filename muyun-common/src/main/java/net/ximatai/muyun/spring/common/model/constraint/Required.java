package net.ximatai.muyun.spring.common.model.constraint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires an effective saved value; type declarations specialize inherited fields for this model only. */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Required.List.class)
public @interface Required {
    String[] fields() default {};
    WriteOperation[] on() default {WriteOperation.INSERT, WriteOperation.UPDATE};

    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List { Required[] value(); }
}
