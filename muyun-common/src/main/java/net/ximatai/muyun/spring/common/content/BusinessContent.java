package net.ximatai.muyun.spring.common.content;

import java.lang.annotation.*;

/** Opts a persisted business text field into source revision tracking. */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(BusinessContents.class)
public @interface BusinessContent {
    ContentPolicy value() default ContentPolicy.AUTO_TRANSLATE;
    /** Stable field identity; retain this value when renaming the Java property. */
    String key() default "";
    /** On a model type, names its own or an inherited property without shadowing it. */
    String field() default "";
}
