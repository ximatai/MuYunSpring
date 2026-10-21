package net.ximatai.muyun.spring.platform.workflow;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowModuleSubmitService {
    private final WorkflowSubmitFacade submitFacade;

    public WorkflowModuleSubmitService(WorkflowSubmitFacade submitFacade) {
        this.submitFacade = submitFacade;
    }

    public WorkflowSubmitResult submitApproval(String moduleAlias, String recordId) {
        return submitFacade.submit(WorkflowSubmitRequest.approval(moduleAlias, recordId));
    }

    public WorkflowSubmitResult submit(WorkflowSubmitRequest request) {
        return submitFacade.submit(request);
    }

    public WorkflowSubmitResult submitApproval(String moduleAlias, String recordId,
                                               String selectedRouteKey, String selectedReason) {
        return submitFacade.submit(WorkflowSubmitRequest.approval(moduleAlias, recordId)
                .withSelectedRoute(selectedRouteKey, selectedReason));
    }

    public WorkflowSubmitResult submitApproval(String moduleAlias, String recordId,
                                               List<WorkflowManualRouteSelection> manualRouteSelections) {
        return submitFacade.submit(WorkflowSubmitRequest.approval(moduleAlias, recordId)
                .withManualRouteSelections(manualRouteSelections));
    }

    /** ID-based workflow entry; alias-based overloads remain available for existing business code. */
    public WorkflowSubmitResult submitWorkflowById(String moduleAlias, String recordId, String definitionId) {
        return submitFacade.submit(WorkflowSubmitRequest.workflowById(moduleAlias, recordId, definitionId));
    }

    public WorkflowSubmitResult submitWorkflowById(String moduleAlias, String recordId, String definitionId,
                                                   String selectedRouteKey, String selectedReason) {
        return submitFacade.submit(WorkflowSubmitRequest.workflowById(moduleAlias, recordId, definitionId)
                .withSelectedRoute(selectedRouteKey, selectedReason));
    }

    public WorkflowSubmitResult submitWorkflowById(String moduleAlias, String recordId, String definitionId,
                                                   List<WorkflowManualRouteSelection> manualRouteSelections) {
        return submitFacade.submit(WorkflowSubmitRequest.workflowById(moduleAlias, recordId, definitionId)
                .withManualRouteSelections(manualRouteSelections));
    }

    public WorkflowSubmitResult submitWorkflow(String moduleAlias, String recordId, String definitionAlias) {
        return submitFacade.submit(WorkflowSubmitRequest.workflow(moduleAlias, recordId, definitionAlias));
    }

    public WorkflowSubmitResult submitWorkflow(String moduleAlias, String recordId, String definitionAlias,
                                               String selectedRouteKey, String selectedReason) {
        return submitFacade.submit(WorkflowSubmitRequest.workflow(moduleAlias, recordId, definitionAlias)
                .withSelectedRoute(selectedRouteKey, selectedReason));
    }

    public WorkflowSubmitResult submitWorkflow(String moduleAlias, String recordId, String definitionAlias,
                                               List<WorkflowManualRouteSelection> manualRouteSelections) {
        return submitFacade.submit(WorkflowSubmitRequest.workflow(moduleAlias, recordId, definitionAlias)
                .withManualRouteSelections(manualRouteSelections));
    }
}
