package net.ximatai.muyun.spring.common.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an HTTP endpoint that is not a module action and may be invoked without login, such as
 * credential exchange or a public deployment catalog. Endpoints that declare neither a module action
 * nor this marker require an authenticated current user; anonymous access is never implied.
 *
 * <p>Only individual handler methods can opt in, so methods added to a controller later stay
 * login-only. A module action that must be reachable anonymously declares
 * {@link ActionAccessMode#ANONYMOUS_ALLOWED} on its action endpoint instead; combining both
 * declarations on one handler is rejected.</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AnonymousEndpoint {
}
