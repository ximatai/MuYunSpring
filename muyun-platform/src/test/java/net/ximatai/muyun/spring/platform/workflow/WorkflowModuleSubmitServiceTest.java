package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowModuleSubmitServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowSubmitFacade submitFacade = mock(WorkflowSubmitFacade.class);
    private final WorkflowModuleSubmitService service = new WorkflowModuleSubmitService(submitFacade);

    @Test
    void shouldSubmitApprovalThroughPlatformFacade() {
        service.submitApproval("sales.contract", "record-1");

        verify(submitFacade).submit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));
    }

    @Test
    void shouldPassSelectedRouteForApprovalSubmit() {
        service.submitApproval("sales.contract", "record-1", "leftRoute", "choose left");

        verify(submitFacade).submit(WorkflowSubmitRequest.approval("sales.contract", "record-1")
                .withSelectedRoute("leftRoute", "choose left"));
    }

    @Test
    void shouldSubmitWorkflowByDefinitionAliasThroughPlatformFacade() {
        service.submitWorkflow("sales.contract", "record-1", "sync");

        verify(submitFacade).submit(WorkflowSubmitRequest.workflow("sales.contract", "record-1", "sync"));
    }

    @Test
    void shouldPassSelectedRouteForWorkflowSubmit() {
        service.submitWorkflow("sales.contract", "record-1", "sync", "leftRoute", "choose left");

        verify(submitFacade).submit(WorkflowSubmitRequest.workflow("sales.contract", "record-1", "sync")
                .withSelectedRoute("leftRoute", "choose left"));
    }    @Test
    void idEntrySupportsDirectSubmissionAndBothRouteSelectionForms() {
        var route = new WorkflowManualRouteSelection("branch", "route", "reason");
        service.submitWorkflowById("sales.contract", "record-1", "definition-id");
        verify(submitFacade).submit(WorkflowSubmitRequest.workflowById("sales.contract", "record-1", "definition-id"));
        service.submitWorkflowById("sales.contract", "record-1", "definition-id", "left", "choose left");
        verify(submitFacade).submit(WorkflowSubmitRequest.workflowById("sales.contract", "record-1", "definition-id").withSelectedRoute("left", "choose left"));
        service.submitWorkflowById("sales.contract", "record-1", "definition-id", java.util.List.of(route));
        verify(submitFacade).submit(WorkflowSubmitRequest.workflowById("sales.contract", "record-1", "definition-id").withManualRouteSelections(java.util.List.of(route)));
    }

}
