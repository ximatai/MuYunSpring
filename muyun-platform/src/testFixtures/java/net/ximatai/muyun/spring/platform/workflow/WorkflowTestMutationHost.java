package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import java.util.function.Supplier;

/** Explicit single-threaded host for in-memory workflow unit tests. Concurrency tests use PostgreSQL. */
public final class WorkflowTestMutationHost {
    private WorkflowTestMutationHost() {}
    public static void install() {
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(Supplier<T> work) { return work.get(); }
            @Override public void lock(String scope, String key) {
                if (scope == null || key == null) throw new IllegalArgumentException("mutation partition is required");
            }
        });
    }
    public static void reset() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }
}
