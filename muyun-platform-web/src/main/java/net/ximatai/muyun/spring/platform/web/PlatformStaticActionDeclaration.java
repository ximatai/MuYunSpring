package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares module actions contributed by an independent static HTTP controller.
 *
 * <p>Unlike {@link PlatformStaticActionScope}, this annotation publishes the actions declared on
 * the controller into the target module's action catalog.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformStaticActionDeclaration {
    String module();
}
