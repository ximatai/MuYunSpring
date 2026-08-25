package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Web-only child resource that may execute under its parent module's menu entry.
 *
 * <p>This is intentionally separate from {@code PlatformStaticModule.parent}: the latter
 * describes the persisted platform module tree, whereas this annotation describes a page
 * delivery relationship only.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformPageEntryChild {
    String parentModuleAlias();
}
