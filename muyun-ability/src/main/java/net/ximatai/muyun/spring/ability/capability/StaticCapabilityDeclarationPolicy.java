package net.ximatai.muyun.spring.ability.capability;

/**
 * Establishes which static-module declaration is authoritative for a capability.
 *
 * <p>{@link #SERVICE_ONLY} capabilities are facts derived from a Java service ability and must
 * never be claimed by {@code @PlatformStaticModule}. {@link #ANNOTATION_OWNED} capabilities are
 * module facts that may be declared by that annotation.</p>
 */
public enum StaticCapabilityDeclarationPolicy {
    SERVICE_ONLY,
    ANNOTATION_OWNED
}
