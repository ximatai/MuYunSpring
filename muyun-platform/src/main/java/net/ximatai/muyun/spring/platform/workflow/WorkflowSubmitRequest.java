package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowSubmitRequest(
        String moduleAlias,
        String recordId,
        boolean approvalRequired,
        String definitionAlias,
        String authOrgId,
        String operatorId,
        Instant operatedAt,
        String selectedRouteKey,
        String selectedReason,
        List<WorkflowManualRouteSelection> manualRouteSelections,
        String reason,
        String definitionId
) {
    public WorkflowSubmitRequest {
        manualRouteSelections = manualRouteSelections == null ? List.of() : List.copyOf(manualRouteSelections);
    }

    /** Preserves the alias-based submission constructor for existing integrations. */
    public WorkflowSubmitRequest(String moduleAlias, String recordId, boolean approvalRequired,
                                 String definitionAlias, String authOrgId, String operatorId, Instant operatedAt,
                                 String selectedRouteKey, String selectedReason,
                                 List<WorkflowManualRouteSelection> manualRouteSelections, String reason) {
        this(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId, operatorId, operatedAt,
                selectedRouteKey, selectedReason, manualRouteSelections, reason, null);
    }

    public WorkflowSubmitRequest(String moduleAlias, String recordId, boolean approvalRequired,
                                 String definitionAlias, String authOrgId, String operatorId, Instant operatedAt,
                                 String selectedRouteKey, String selectedReason,
                                 List<WorkflowManualRouteSelection> manualRouteSelections) {
        this(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId, operatorId, operatedAt,
                selectedRouteKey, selectedReason, manualRouteSelections, null);
    }

    public WorkflowSubmitRequest withReason(String reason) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }

    public WorkflowSubmitRequest(String moduleAlias,
                                 String recordId,
                                 boolean approvalRequired,
                                 String definitionAlias,
                                 String authOrgId,
                                 String operatorId,
                                 Instant operatedAt,
                                 String selectedRouteKey,
                                 String selectedReason) {
        this(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId, operatorId, operatedAt,
                selectedRouteKey, selectedReason, List.of());
    }

    public WorkflowSubmitRequest(String moduleAlias,
                                 String recordId,
                                 boolean approvalRequired,
                                 String definitionAlias,
                                 String operatorId,
                                 Instant operatedAt) {
        this(moduleAlias, recordId, approvalRequired, definitionAlias, null, operatorId, operatedAt, null, null);
    }

    public WorkflowSubmitRequest(String moduleAlias,
                                 String recordId,
                                 boolean approvalRequired,
                                 String definitionAlias,
                                 String authOrgId,
                                 String operatorId,
                                 Instant operatedAt) {
        this(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId, operatorId, operatedAt, null, null);
    }

    public static WorkflowSubmitRequest approval(String moduleAlias, String recordId) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, true, null, null, null, null, null, null);
    }

    public static WorkflowSubmitRequest workflow(String moduleAlias, String recordId, String definitionAlias) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, false, definitionAlias, null, null, null, null, null);
    }

    /** Starts a specific non-approval workflow using its stable definition identity. */
    public static WorkflowSubmitRequest workflowById(String moduleAlias, String recordId, String definitionId) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, false, null, null, null, null,
                null, null, List.of(), null, definitionId);
    }

    public WorkflowSubmitRequest withOperator(String operatorId) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }

    public WorkflowSubmitRequest withAuthOrgId(String authOrgId) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }

    public WorkflowSubmitRequest withOperatedAt(Instant operatedAt) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }

    public WorkflowSubmitRequest withSelectedRoute(String selectedRouteKey, String selectedReason) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }

    public WorkflowSubmitRequest withManualRouteSelections(
            List<WorkflowManualRouteSelection> manualRouteSelections) {
        return new WorkflowSubmitRequest(moduleAlias, recordId, approvalRequired, definitionAlias, authOrgId,
                operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections, reason, definitionId);
    }
}
