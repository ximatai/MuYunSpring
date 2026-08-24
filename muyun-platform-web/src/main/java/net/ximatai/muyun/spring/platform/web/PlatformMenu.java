package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformMenu {
    String id() default "";

    String parent();

    String title() default "";

    /**
     * Optional canonical module opened by this menu. When omitted, the declaring static module
     * remains the target. This lets a business expose a scoped entry without duplicating CRUD.
     */
    String moduleAlias() default "";

    int order() default 100;

    MenuOpenMode openMode() default MenuOpenMode.TAB;

    boolean enabled() default true;
}
