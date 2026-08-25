package net.ximatai.muyun.spring.ability.reference;

import java.util.Optional;

/** Resolves a reference target without exposing whether it is static or dynamic. */
@FunctionalInterface
public interface ReferenceTargetResolver {
    ReferenceTargetResolver NONE = target -> Optional.empty();

    Optional<ReferenceAbility<?>> resolve(ReferenceTarget target);

    /**
     * Resolves the declared outgoing reference of one target entity.
     *
     * <p>Selection projections use this metadata to compile a relative dot path into the
     * same {@link ReferenceLoadPath} executed by ordinary reference loads.  Implementations
     * that only expose direct reference reads may retain the empty default.</p>
     */
    default Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
        return Optional.empty();
    }
}
