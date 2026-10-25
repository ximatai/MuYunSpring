package net.ximatai.muyun.spring.iam.federation;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Assembles a component only when {@code muyun.iam.federation.enabled=true}. Services, HTTP entries and
 * management modules of external identity federation stay absent from applications that do not opt in.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ExternalIdentityFederationCondition.class)
public @interface ConditionalOnExternalIdentityFederation {
}
