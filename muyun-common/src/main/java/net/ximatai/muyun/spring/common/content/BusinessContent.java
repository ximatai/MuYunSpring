package net.ximatai.muyun.spring.common.content;

import java.lang.annotation.*;

/** Opts a persisted business text field into source revision tracking. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BusinessContent {
    ContentPolicy value() default ContentPolicy.AUTO_TRANSLATE;
    /** Stable field identity; retain this value when renaming the Java property. */
    String key() default "";
}
