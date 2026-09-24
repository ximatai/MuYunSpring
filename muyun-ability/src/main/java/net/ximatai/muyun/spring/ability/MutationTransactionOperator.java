package net.ximatai.muyun.spring.ability;

import java.util.function.Supplier;

/** Opens one transaction for a complete standard mutation, including its aggregate and lifecycle when available. */
@FunctionalInterface
public interface MutationTransactionOperator {
    MutationTransactionOperator NONE = Supplier::get;

    <T> T execute(Supplier<T> work);

    /** Holds a database lock until the outermost transaction completes; never silently runs unlocked. */
    default void lock(String scope, String key) {
        throw new IllegalStateException("transactional mutation locks are not installed");
    }

    /**
     * Isolates one DAO write so a failed statement is rolled back before constraint diagnostics
     * query the current transaction. Transactional hosts must provide a savepoint; no lifecycle
     * callbacks or independently committed writes belong inside this boundary.
     */
    default <T> T executeStatement(Supplier<T> work) {
        return work.get();
    }
}
