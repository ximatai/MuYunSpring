package net.ximatai.muyun.spring.iam.federation;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** The mock provider needs federation and its own explicit switch. */
public final class MockIdentityProviderCondition implements Condition {
    static final String ENABLED_PROPERTY = "muyun.iam.federation.mock.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ExternalIdentityFederationCondition().matches(context, metadata)
                && Boolean.TRUE.equals(context.getEnvironment().getProperty(ENABLED_PROPERTY, Boolean.class, false));
    }
}
