package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowSubmitFacadeTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowDefinitionSelector selector = mock(WorkflowDefinitionSelector.class);
    private final WorkflowRuntimeSubmitService runtimeSubmitService = mock(WorkflowRuntimeSubmitService.class);
    private final WorkflowApprovalSummaryWriter writer = mock(WorkflowApprovalSummaryWriter.class);
    private final WorkflowModuleRecordGuard recordGuard = mock(WorkflowModuleRecordGuard.class);
    private final WorkflowSubmitFacade facade = new WorkflowSubmitFacade(
            selector, runtimeSubmitService, Optional.of(writer), List.of(recordGuard));

    @Test
    void shouldSubmitThroughSelectedDefinitionAndWriteApprovalSummary() {
        WorkflowSubmitRequest request = WorkflowSubmitRequest.approval("sales.contract", "record-1")
                .withOperator("user-1")
                .withOperatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowDefinitionSelection selection = selection(true);
        WorkflowSubmitDraft draft = draft(true);
        when(selector.select(request)).thenReturn(selection);
        when(runtimeSubmitService.submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), null, null,
                List.of()))
                .thenReturn(draft);

        WorkflowSubmitResult result = facade.submit(request);

        assertThat(result.draft()).isEqualTo(draft);
        assertThat(result.approvalSummaryWritten()).isTrue();
        var order = inOrder(recordGuard, selector, runtimeSubmitService);
        order.verify(recordGuard).beforeSubmit(request);
        order.verify(selector).select(request);
        order.verify(runtimeSubmitService).submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), null, null,
                List.of());
        var captor = forClass(WorkflowApprovalSummary.class);
        verify(writer).writeSubmitted(captor.capture());
        assertThat(captor.getValue().approvalInstanceId()).isEqualTo("instance-1");
        assertThat(captor.getValue().approvalStatus()).isEqualTo(WorkflowApprovalStatus.PROCESSING);
        assertThat(captor.getValue().approvalSubmittedBy()).isEqualTo("user-1");
        assertThat(captor.getValue().approvalSubmittedAt()).isEqualTo(Instant.parse("2026-06-05T01:00:00Z"));
    }

    @Test
    void shouldAllowNonApprovalWorkflowWithoutApprovalSummaryWriteback() {
        WorkflowSubmitRequest request = WorkflowSubmitRequest.workflow("sales.contract", "record-1", "sync")
                .withOperator("user-1")
                .withOperatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowDefinitionSelection selection = selection(false);
        WorkflowSubmitDraft draft = draft(false);
        when(selector.select(request)).thenReturn(selection);
        when(runtimeSubmitService.submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), null, null,
                List.of()))
                .thenReturn(draft);

        WorkflowSubmitResult result = facade.submit(request);

        assertThat(result.approvalSummaryWritten()).isFalse();
        verifyNoInteractions(writer);
    }

    @Test
    void shouldNormalizeAndPassSubmitRouteSelection() {
        WorkflowSubmitRequest request = WorkflowSubmitRequest.workflow("sales.contract", "record-1", "sync")
                .withOperator("user-1")
                .withOperatedAt(Instant.parse("2026-06-05T01:00:00Z"))
                .withSelectedRoute(" leftRoute ", " choose left ");
        WorkflowDefinitionSelection selection = selection(false);
        WorkflowSubmitDraft draft = draft(false);
        WorkflowSubmitRequest normalized = WorkflowSubmitRequest.workflow("sales.contract", "record-1", "sync")
                .withOperator("user-1")
                .withOperatedAt(Instant.parse("2026-06-05T01:00:00Z"))
                .withSelectedRoute(" leftRoute ", " choose left ");
        when(selector.select(normalized)).thenReturn(selection);
        when(runtimeSubmitService.submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                " leftRoute ", " choose left ", List.of()))
                .thenReturn(draft);

        WorkflowSubmitResult result = facade.submit(request);

        assertThat(result.draft()).isEqualTo(draft);
        verify(runtimeSubmitService).submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                " leftRoute ", " choose left ", List.of());
    }

    @Test
    void shouldPreviewThroughSelectedDefinitionWithoutPersistingOrWritingSummary() {
        WorkflowSubmitRequest request = WorkflowSubmitRequest.approval("sales.contract", "record-1")
                .withOperator("user-1")
                .withOperatedAt(Instant.parse("2026-06-05T01:00:00Z"))
                .withSelectedRoute("leftRoute", "choose left");
        WorkflowDefinitionSelection selection = selection(true);
        WorkflowSubmitDraft draft = draft(true);
        when(selector.select(request)).thenReturn(selection);
        when(runtimeSubmitService.preview(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", "choose left", List.of()))
                .thenReturn(draft);

        WorkflowSubmitPreview preview = facade.preview(request);

        assertThat(preview.selection()).isEqualTo(selection);
        assertThat(preview.draft()).isEqualTo(draft);
        var order = inOrder(recordGuard, selector, runtimeSubmitService);
        order.verify(recordGuard).beforeSubmit(request);
        order.verify(selector).select(request);
        order.verify(runtimeSubmitService).preview(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", "choose left", List.of());
        verifyNoInteractions(writer);
    }

    private WorkflowDefinitionSelection selection(boolean approvalEnabled) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("definition-1");
        definition.setModuleAlias("sales.contract");
        definition.setApprovalEnabled(approvalEnabled);
        WorkflowVersion version = new WorkflowVersion();
        version.setId("version-1");
        version.setVersionNo(1);
        return new WorkflowDefinitionSelection(definition, version, List.of(), List.of());
    }

    private WorkflowSubmitDraft draft(boolean approvalEnabled) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setApprovalEnabled(approvalEnabled);
        instance.setApprovalStatus(approvalEnabled ? WorkflowApprovalStatus.PROCESSING : WorkflowApprovalStatus.NONE);
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        return new WorkflowSubmitDraft(instance, List.of(), List.of(), List.of(), List.of(),
                new WorkflowActivationResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false));
    }
}
