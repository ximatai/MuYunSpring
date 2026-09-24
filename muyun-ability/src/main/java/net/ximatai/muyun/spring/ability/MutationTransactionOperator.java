package net.ximatai.muyun.spring.ability;

import java.util.function.Supplier;

/** Opens one transaction for a complete standard mutation, including its aggregate and lifecycle when available. */
@FunctionalInterface
public interface MutationTransactionOperator {
    MutationTransactionOperator NONE = Supplier::get;

    <T> T execute(Supplier<T> work);
}
