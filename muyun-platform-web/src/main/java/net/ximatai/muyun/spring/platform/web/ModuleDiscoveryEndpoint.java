package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an authenticated module endpoint that describes delivery metadata without reading or
 * changing tenant business records.
 *
 * <p>Discovery endpoints remain available before an operator chooses a tenant so the platform
 * can assemble the tenant picker and the rest of a module page. They still participate in action
 * authorization; only the active-tenant requirement is deferred to subsequent business calls.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleDiscoveryEndpoint {
}
