package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Multiple menu entries of one canonical module share its action and record authorization. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformMenus {
    PlatformMenu[] value();
}
