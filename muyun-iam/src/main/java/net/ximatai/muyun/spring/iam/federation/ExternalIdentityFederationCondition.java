package net.ximatai.muyun.spring.iam.federation;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Federation is opt-in; an absent or invalid switch keeps it disabled. */
final class ExternalIdentityFederationCondition implements Condition {
    static final String ENABLED_PROPERTY = "muyun.iam.federation.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.TRUE.equals(context.getEnvironment().getProperty(ENABLED_PROPERTY, Boolean.class, false));
    }
}
