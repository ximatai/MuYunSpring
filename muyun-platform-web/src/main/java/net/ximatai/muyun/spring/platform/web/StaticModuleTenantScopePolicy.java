package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit delivery policy for a static module whose action endpoints may run without a selected
 * business tenant.
 *
 * <p>Most static CRUD modules derive their requirement from their tenant-aware service. Applying
 * this annotation is reserved for governance modules that deliberately retain a system workspace
 * as well as tenant-scoped operations; it does not change the ambient tenant context.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StaticModuleTenantScopePolicy {
    boolean requireActiveTenant() default true;
}
