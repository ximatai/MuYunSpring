package net.ximatai.muyun.spring.common.model.constraint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit write-only processing, declared on a field or on a model's named inherited fields. */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(NormalizeText.List.class)
public @interface NormalizeText {
    String[] fields() default {};
    TextNormalization value() default TextNormalization.TRIM;

    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List { NormalizeText[] value(); }
}
