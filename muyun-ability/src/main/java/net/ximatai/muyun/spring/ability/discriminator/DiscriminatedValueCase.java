package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** One declared branch of a {@link DiscriminatedValue}. */
@Retention(RetentionPolicy.RUNTIME)
public @interface DiscriminatedValueCase {
    String when();

    DiscriminatedValueSource source();

    String fixedValue() default "";

    String sourceField() default "";

    /** Static target service, when the branch is a reference. */
    Class<?> target() default Void.class;

    /** Alias fallback keeps the declaration independent from another domain module. */
    String moduleAlias() default "";

    String entityAlias() default "";

    ReferenceCardinality cardinality() default ReferenceCardinality.ONE;

    ReferenceCandidateBinding[] candidateBindings() default {};

    ReferenceTenantScope tenantScope() default ReferenceTenantScope.SAME_TENANT;

    ReferenceIntegrity integrity() default @ReferenceIntegrity;
}
