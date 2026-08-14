package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collection;

/**
 * Domain-read entry point for enriching declared reference read facts in batches.
 *
 * <p>This deliberately exposes neither SQL projection planning nor target-service lookup.
 * A domain facade supplies its already-authorized root records; the platform applies only
 * the {@link ReferenceLoad} facts declared by that root model.</p>
 */
public final class ReferenceReadFacade {
    private final ReferenceLoadResolver resolver;

    public ReferenceReadFacade(ReferenceLoadResolver resolver) {
        this.resolver = resolver == null ? ReferenceLoadResolver.NONE : resolver;
    }

    public void enrich(CrudAbility<?> ability, Collection<? extends EntityContract> records) {
        resolver.populateAll(ability, records);
    }
}
