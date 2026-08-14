package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collection;

/**
 * Domain-read entry point for enriching declared reference read facts in batches.
 *
 * <p>This deliberately exposes neither SQL projection planning nor target-service lookup.
 * A domain facade supplies its already-authorized root records; the platform applies only
 * the {@link ReferenceLoad} and {@link ReferencedBy} facts declared by that root model.</p>
 */
public final class ReferenceReadFacade {
    private final ReferenceLoadResolver referenceLoadResolver;
    private final ReferencedByResolver referencedByResolver;

    public ReferenceReadFacade(ReferenceLoadResolver resolver) {
        this(resolver, ReferencedByResolver.NONE);
    }

    public ReferenceReadFacade(ReferenceLoadResolver referenceLoadResolver,
                               ReferencedByResolver referencedByResolver) {
        this.referenceLoadResolver = referenceLoadResolver == null ? ReferenceLoadResolver.NONE : referenceLoadResolver;
        this.referencedByResolver = referencedByResolver == null ? ReferencedByResolver.NONE : referencedByResolver;
    }

    public void enrich(CrudAbility<?> ability, Collection<? extends EntityContract> records) {
        referenceLoadResolver.populateAll(ability, records);
        referencedByResolver.populateAll(ability, records);
    }
}
