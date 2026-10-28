package net.ximatai.muyun.spring.common.model.constraint;

import java.lang.annotation.*;

/** Declares a named partial index; unique indexes are tenant-scoped and checked by standard writes. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ConditionalIndexes.class)
public @interface ConditionalIndex {
    String name();
    String[] fields();
    boolean unique() default false;
    Condition[] when();
    String message() default "";

    @Target({}) @Retention(RetentionPolicy.RUNTIME)
    @interface Condition {
        String field();
        IndexCondition.Operator operator();
        String[] values() default {};
    }
}
