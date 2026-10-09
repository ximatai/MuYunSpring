package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowManualBranchSelectorResolverTest {
    private final WorkflowManualBranchSelectorResolver resolver = new WorkflowManualBranchSelectorResolver();

    @Test
    void customStartKeyResolvesOnlyTheInitiatorWithoutRequiringProcessedTasks() {
        var start = node("begin", WorkflowNodeType.START);
        var resolution = resolver.resolve(instance(), List.of(start), List.of(), "begin", null, "initiator");
        assertThat(resolution.selectable()).isTrue();
        assertThat(resolution.selectorNodeKey()).isEqualTo("begin");
        assertThat(resolution.resolvedUserId()).isEqualTo("initiator");
        var other = resolver.resolve(instance(), List.of(start), List.of(), "begin", null, "other");
        assertThat(other.selectable()).isFalse();
        assertThat(other.unselectableReason()).isEqualTo(WorkflowManualBranchSelectorResolver.SELECTOR_NOT_OPERATOR);
    }

    @Test
    void legacyStartReferenceRemainsCompatibleWithoutRuntimeNode() {
        assertThat(resolver.resolve(instance(), List.of(), List.of(), "START", null, "initiator").selectable()).isTrue();
        assertThat(resolver.resolve(instance(), List.of(), List.of(), "start", null, "initiator").selectable()).isTrue();
    }

    @Test
    void arbitraryMissingNodeIsNotAssumedToBeStart() {
        var missing = resolver.resolve(instance(), List.of(), List.of(), "begin", null, "initiator");
        assertThat(missing.selectable()).isFalse();
        assertThat(missing.unselectableReason()).isEqualTo(WorkflowManualBranchSelectorResolver.SELECTOR_NOT_FOUND);
    }

    @Test
    void actualNodeTypeTakesPrecedenceOverTheLegacyStartName() {
        var resolution = resolver.resolve(instance(), List.of(node("START", WorkflowNodeType.MILESTONE)),
                List.of(), "START", null, "initiator");
        assertThat(resolution.selectable()).isFalse();
        assertThat(resolution.unselectableReason()).isEqualTo(WorkflowManualBranchSelectorResolver.SELECTOR_UNSUPPORTED);
    }

    private WorkflowNodeInstance node(String key, WorkflowNodeType type) {
        var node = new WorkflowNodeInstance(); node.setNodeKey(key); node.setNodeType(type); return node;
    }

    private WorkflowInstance instance() {
        var instance = new WorkflowInstance(); instance.setStartedBy("initiator"); return instance;
    }
}
