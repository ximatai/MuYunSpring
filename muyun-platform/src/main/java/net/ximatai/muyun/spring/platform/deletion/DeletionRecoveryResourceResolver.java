package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.SoftDeleteAbility;

import java.util.Optional;

/**
 * Resolves the resource-owning soft-delete ability for one persisted deletion entry.
 *
 * <p>The deletion journal identifies resources by runtime aliases. It must not
 * depend on a startup-time scan of every {@link SoftDeleteAbility}: dynamic
 * ability instances have no stable application bean identity, and soft deletion
 * alone does not opt a resource into an operator-facing recycle bin.</p>
 */
public interface DeletionRecoveryResourceResolver {
    boolean supports(DeletionEntry entry);

    Optional<SoftDeleteAbility<?>> resolve(DeletionEntry entry);

    /** Verifies current ownership for a child whose cleanup entry is governed by its parent aggregate. */
    default boolean canPurgeAggregateChild(DeletionEntry entry, DeletionEntry parent) {
        return false;
    }
}
