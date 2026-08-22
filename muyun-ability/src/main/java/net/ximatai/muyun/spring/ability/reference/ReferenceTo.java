package net.ximatai.muyun.spring.ability.reference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceTo {
    /** Preferred static declaration: a target service exposing {@code MODULE_ALIAS}. */
    Class<?> target() default Void.class;

    /** Alias fallback for dynamic, external, or otherwise non-class targets. */
    String moduleAlias() default "";

    /** Entity alias paired with {@link #moduleAlias()}. */
    String entityAlias() default "";

    ReferenceCardinality cardinality() default ReferenceCardinality.ONE;

    /**
     * Declarative candidate constraints. They are enforced for list, tree and
     * value translation by the source-field reference resolver.
     */
    ReferenceCandidateBinding[] candidateBindings() default {};

    /** Tenant boundary for candidate lookup and value translation. */
    ReferenceTenantScope tenantScope() default ReferenceTenantScope.SAME_TENANT;

    ReferenceIntegrity integrity() default @ReferenceIntegrity;
}
