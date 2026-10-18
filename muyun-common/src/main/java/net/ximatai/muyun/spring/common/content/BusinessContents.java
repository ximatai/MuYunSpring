package net.ximatai.muyun.spring.common.content;

import java.lang.annotation.*;

/** Repeatable declarations for distinct business text properties. */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BusinessContents {
    BusinessContent[] value();
}
