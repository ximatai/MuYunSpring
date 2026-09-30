package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a static platform module's domain identity and delivery-independent facts.
 *
 * <p>HTTP paths and endpoint projection are declared by the Web delivery layer, so a
 * static module can exist without a Web dependency.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformStaticModule {
    /** Static application declaration that owns this module. */
    Class<?> application();

    String alias();

    String title();

    /** Short optional business-purpose hint for discovery. */
    String description() default "";

    String parent() default "";

    String route() default "";

    String externalUrl() default "";

    EntityCapability[] capabilities() default {};
}
