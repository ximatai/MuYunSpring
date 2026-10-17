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
