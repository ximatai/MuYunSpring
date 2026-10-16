package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimePluginDispatcherTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    @BeforeEach
    void setUp() {
        clearTransactionState();
    }

    @AfterEach
    void tearDown() {
        clearTransactionState();
    }

    @Test
    void shouldFilterAndDispatchPluginsInStableOrder() {
        List<String> calls = new ArrayList<>();
        WorkflowRuntimePluginDispatcher dispatcher = new WorkflowRuntimePluginDispatcher(List.of(
                plugin("late", 20, "sales.contract", "approve", calls),
                plugin("early-b", 10, "sales.contract", "approve", calls),
                plugin("early-a", 10, "sales.contract", "approve", calls),
                plugin("other-node", 1, "sales.contract", "review", calls),
                plugin("other-module", 1, "sales.order", "approve", calls)
        ));

        dispatcher.dispatch(new WorkflowRuntimePluginContext(WorkflowRuntimePluginEventType.BEFORE_APPROVE,
                "approve", "sales.contract", "record-1", "instance-1", "approve", "task-1",
                "user-1", null, null, null, "agree", null, null, null));

        assertThat(calls).containsExactly("early-a", "early-b", "late");
    }

    @Test
    void shouldRunSynchronousPluginsBeforeAfterCommitPluginsForAfterEventsWithoutTransaction() {
        List<String> calls = new ArrayList<>();
        WorkflowRuntimePluginDispatcher dispatcher = new WorkflowRuntimePluginDispatcher(List.of(
                plugin("after", 1, "sales.contract", "approve",
                        Set.of(WorkflowRuntimePluginEventType.BEFORE_APPROVE, WorkflowRuntimePluginEventType.AFTER_APPROVE),
                        WorkflowRuntimePluginDispatchTiming.AFTER_COMMIT, calls),
                plugin("sync", 20, "sales.contract", "approve",
                        Set.of(WorkflowRuntimePluginEventType.BEFORE_APPROVE, WorkflowRuntimePluginEventType.AFTER_APPROVE),
                        WorkflowRuntimePluginDispatchTiming.SYNCHRONOUS, calls)
        ));

        dispatcher.dispatch(context(WorkflowRuntimePluginEventType.BEFORE_APPROVE));
        assertThat(calls).containsExactly("sync");

        calls.clear();
        dispatcher.dispatch(context(WorkflowRuntimePluginEventType.AFTER_APPROVE));
        assertThat(calls).containsExactly("sync", "after");
    }

    @Test
    void shouldRunAfterCommitPluginsOnlyAfterSpringTransactionCommit() {
        List<String> calls = new ArrayList<>();
        WorkflowRuntimePluginDispatcher dispatcher = new WorkflowRuntimePluginDispatcher(List.of(
                plugin("after-a", 20, "sales.contract", "approve",
                        Set.of(WorkflowRuntimePluginEventType.AFTER_APPROVE),
                        WorkflowRuntimePluginDispatchTiming.AFTER_COMMIT, calls),
                plugin("after-b", 10, "sales.contract", "approve",
                        Set.of(WorkflowRuntimePluginEventType.AFTER_APPROVE),
                        WorkflowRuntimePluginDispatchTiming.AFTER_COMMIT, calls)
        ));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        dispatcher.dispatch(context(WorkflowRuntimePluginEventType.AFTER_APPROVE));

        assertThat(calls).isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(calls).containsExactly("after-b", "after-a");
    }

    private WorkflowRuntimePlugin plugin(String key, int order, String moduleAlias, String nodeKey,
                                         List<String> calls) {
        return plugin(key, order, moduleAlias, nodeKey, Set.of(WorkflowRuntimePluginEventType.BEFORE_APPROVE),
                WorkflowRuntimePluginDispatchTiming.SYNCHRONOUS, calls);
    }

    private WorkflowRuntimePlugin plugin(String key, int order, String moduleAlias, String nodeKey,
                                         Set<WorkflowRuntimePluginEventType> eventTypes,
                                         WorkflowRuntimePluginDispatchTiming dispatchTiming,
                                         List<String> calls) {
        return new WorkflowRuntimePlugin() {
            @Override
            public String pluginKey() {
                return key;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public WorkflowRuntimePluginDispatchTiming dispatchTiming() {
                return dispatchTiming;
            }

            @Override
            public Set<WorkflowRuntimePluginEventType> eventTypes() {
                return eventTypes;
            }

            @Override
            public String moduleAlias() {
                return moduleAlias;
            }

            @Override
            public String nodeKey() {
                return nodeKey;
            }

            @Override
            public void handle(WorkflowRuntimePluginContext context) {
                calls.add(key);
            }
        };
    }

    private WorkflowRuntimePluginContext context(WorkflowRuntimePluginEventType eventType) {
        return new WorkflowRuntimePluginContext(eventType, "approve", "sales.contract", "record-1",
                "instance-1", "approve", "task-1", "user-1", null, null, null, "agree",
                null, null, null);
    }

    private void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
