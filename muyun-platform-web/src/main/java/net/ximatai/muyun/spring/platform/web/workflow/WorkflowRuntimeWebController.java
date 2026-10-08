package net.ximatai.muyun.spring.platform.web.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEvent;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAddSignSegment;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskContinueResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskProcessBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskRuntimeService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNoticeReadStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRejectResubmitMode;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeReadFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeRenderBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTask;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskAvailableAction;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchCard;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchQueryRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchSort;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchStats;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAssignmentKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualBranchCandidatePrecheckView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualBranchCandidateView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import net.ximatai.muyun.spring.platform.workflow.WorkflowOvertimeStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeAddSignExplanationView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitPreviewView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitReadFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitStatusView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import net.ximatai.muyun.spring.web.BusinessMutation;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/workflow/runtime")
public class WorkflowRuntimeWebController {
    private final WorkflowRuntimeReadFacade runtimeReadFacade;
    private final WorkflowTaskActionFacade taskActionFacade;
    private final WorkflowInstanceActionFacade instanceActionFacade;
    private final WorkflowModuleTaskRuntimeService moduleTaskRuntimeService;
    private final WorkflowSubmitFacade submitFacade;
    private final WorkflowSubmitReadFacade submitReadFacade;

    public WorkflowRuntimeWebController(WorkflowRuntimeReadFacade runtimeReadFacade,
                                        WorkflowTaskActionFacade taskActionFacade,
                                        WorkflowInstanceActionFacade instanceActionFacade,
                                        WorkflowModuleTaskRuntimeService moduleTaskRuntimeService,
                                        WorkflowSubmitFacade submitFacade,
                                        WorkflowSubmitReadFacade submitReadFacade) {
        this.runtimeReadFacade = runtimeReadFacade;
        this.taskActionFacade = taskActionFacade;
        this.instanceActionFacade = instanceActionFacade;
        this.moduleTaskRuntimeService = moduleTaskRuntimeService;
        this.submitFacade = submitFacade;
        this.submitReadFacade = submitReadFacade;
    }

    @GetMapping("/instance/{instanceId}/bundle")
    public WorkflowRuntimeRenderBundle renderBundle(@PathVariable String instanceId) {
        return runtimeReadFacade.renderBundle(instanceId);
    }

    @GetMapping("/instance/{instanceId}/manual-branches")
    public WebListResponse<WorkflowManualBranchCandidateView> manualBranchCandidates(@PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.manualBranchCandidates(instanceId));
    }

    @PostMapping("/instance/{instanceId}/manual-branches")
    public WebListResponse<WorkflowManualBranchCandidateView> planManualBranchCandidates(@PathVariable String instanceId,
            @RequestBody WorkflowManualBranchPlanWebRequest request) {
        if (request == null) throw new PlatformException("workflow manual branch plan request must not be null");
        return new WebListResponse<>(runtimeReadFacade.manualBranchCandidates(instanceId, request.taskId(),
                request.manualRouteSelections(), currentOperatorId()));
    }

    @GetMapping("/instance/{instanceId}/manual-branch-candidate-prechecks")
    public WebListResponse<WorkflowManualBranchCandidatePrecheckView> manualBranchCandidatePrechecks(
            @PathVariable String instanceId,
            @RequestParam(required = false) String operatorId) {
        return new WebListResponse<>(runtimeReadFacade.manualBranchCandidatePrechecks(instanceId,
                currentOperatorId()));
    }

    @GetMapping("/instance/{instanceId}/tasks")
    public WebListResponse<WorkflowTask> instanceTasks(@PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.instanceTasks(instanceId));
    }

    @GetMapping("/instance/{instanceId}/events")
    public WebListResponse<WorkflowEvent> instanceEvents(@PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.instanceEvents(instanceId));
    }

    @GetMapping("/instance/{instanceId}/add-sign-explanations")
    public WebListResponse<WorkflowRuntimeAddSignExplanationView> addSignExplanations(
            @PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.addSignExplanations(instanceId));
    }

    @PostMapping("/record/{moduleAlias}/{recordId}/submit/status")
    public WorkflowSubmitStatusView submitStatus(@PathVariable String moduleAlias,
                                                 @PathVariable String recordId,
                                                 @RequestBody(required = false) WorkflowSubmitWebRequest request) {
        return submitReadFacade.status(submitRequest(moduleAlias, recordId, request, false));
    }

    @PostMapping("/record/{moduleAlias}/{recordId}/submit/manual-branches")
    public WebListResponse<WorkflowManualBranchCandidateView> submitManualBranches(@PathVariable String moduleAlias,
            @PathVariable String recordId, @RequestBody(required = false) WorkflowSubmitWebRequest request) {
        return new WebListResponse<>(submitReadFacade.manualBranches(submitRequest(moduleAlias, recordId, request, false)));
    }

    @PostMapping("/record/{moduleAlias}/{recordId}/submit/preview")
    public WorkflowSubmitPreviewView submitPreview(@PathVariable String moduleAlias,
                                                   @PathVariable String recordId,
                                                   @RequestBody(required = false) WorkflowSubmitWebRequest request) {
        return submitReadFacade.preview(submitRequest(moduleAlias, recordId, request, true));
    }

    @CustomActionEndpoint(value = "submitApproval", title = "Submit Approval",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "recordId")
    @PostMapping("/record/{moduleAlias}/{recordId}/actions/submitApproval")
    @BusinessMutation(actionContextRequired = false)
    public WorkflowSubmitResult submitApproval(@PathVariable String moduleAlias,
                                               @PathVariable String recordId,
                                               @RequestBody(required = false) WorkflowSubmitWebRequest request) {
        return submitFacade.submit(submitRequest(moduleAlias, recordId, request, true));
    }

    @PostMapping("/instance/{instanceId}/actions")
    public WebListResponse<WorkflowTaskAvailableAction> instanceAvailableActions(
            @PathVariable String instanceId,
            @RequestBody(required = false) WorkflowOperatorWebRequest request) {
        return new WebListResponse<>(runtimeReadFacade.instanceAvailableActions(instanceId,
                currentOperatorIdOrNull()));
    }

    @PostMapping("/instance/{instanceId}/actions/{actionCode}")
    @BusinessMutation(actionContextRequired = false)
    public WorkflowInstanceActionResult executeInstanceAction(
            @PathVariable String instanceId,
            @PathVariable String actionCode,
            @RequestBody(required = false) WorkflowInstanceActionWebRequest request) {
        return instanceActionFacade.execute(actionCode, new WorkflowInstanceActionRequest(instanceId,
                currentOperatorId(),
                request == null ? null : request.reason(),
                null));
    }

    @PostMapping("/task/{taskId}/actions/{actionCode}")
    @BusinessMutation(actionContextRequired = false)
    public WorkflowTaskActionResult executeTaskAction(
            @PathVariable String taskId,
            @PathVariable String actionCode,
            @RequestBody(required = false) WorkflowTaskActionWebRequest request) {
        WorkflowTaskActionRequest actionRequest = WorkflowTaskActionRequest.builder(taskId, currentOperatorId())
                .targetAssigneeId(request == null ? null : request.targetAssigneeId())
                .addSignSegment(request == null ? null : request.addSignSegment())
                .rejectResubmitMode(rejectResubmitMode(request == null ? null : request.rejectResubmitMode()))
                .reason(request == null ? null : request.reason())
                .selectedRoute(request == null ? null : request.selectedRouteKeyOrDirectLinkKey(),
                        request == null ? null : request.selectedReason())
                .manualRouteSelections(request == null ? null : request.manualRouteSelections())
                .designerSnapshot(request == null ? null : jsonText(request.semanticJson()),
                        request == null ? null : jsonText(request.layoutJson()))
                .build();
        return taskActionFacade.execute(actionCode, actionRequest);
    }

    @PostMapping("/task/{taskId}/read")
    @BusinessMutation(actionContextRequired = false)
    public WorkflowTaskActionResult readNoticeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) WorkflowTaskActionWebRequest request) {
        return taskActionFacade.execute("read", WorkflowTaskActionRequest.builder(taskId, currentOperatorId())
                .reason(request == null ? null : request.reason())
                .build());
    }

    @GetMapping("/workbench/modules")
    public java.util.Map<String, String> workbenchModules() {
        currentOperatorId();
        return runtimeReadFacade.workbenchModules();
    }

    @PostMapping("/workbench/{board}/page")
    public net.ximatai.muyun.spring.web.WebPageResponse<WorkflowWorkbenchCard> workbenchPage(
            @PathVariable String board, @RequestBody(required = false) WorkflowWorkbenchPageWebRequest request) {
        var query = normalizeWorkbenchRequest(request == null ? null : request.query());
        var result = runtimeReadFacade.workbenchPage(board, currentOperatorId(), page(query.page()),
                query.toQueryRequest(), request == null ? null : request.keyword());
        return net.ximatai.muyun.spring.web.WebPageResponse.from(result.page(), java.util.Map.of("modules", result.modules()));
    }

    @GetMapping("/instance/{instanceId}/tasks/view")
    public WebListResponse<net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryTaskView> taskViews(@PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.instanceTaskViews(instanceId));
    }

    @GetMapping("/instance/{instanceId}/events/view")
    public WebListResponse<net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryEventView> eventViews(@PathVariable String instanceId) {
        return new WebListResponse<>(runtimeReadFacade.instanceEventViews(instanceId));
    }

    @PostMapping("/workbench/todo/query")
    public WebListResponse<WorkflowWorkbenchCard> todoCards(
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return new WebListResponse<>(runtimeReadFacade.todoCards(currentOperatorId(),
                page(normalized.page()), normalized.toQueryRequest()));
    }

    @PostMapping("/workbench/done/query")
    public WebListResponse<WorkflowWorkbenchCard> doneCards(
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return new WebListResponse<>(runtimeReadFacade.doneCards(currentOperatorId(),
                page(normalized.page()), normalized.toQueryRequest()));
    }

    @PostMapping("/workbench/notice/query")
    public WebListResponse<WorkflowWorkbenchCard> noticeCards(
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return new WebListResponse<>(runtimeReadFacade.noticeCards(currentOperatorId(),
                page(normalized.page()), normalized.toQueryRequest()));
    }

    @PostMapping("/workbench/tracking/query")
    public WebListResponse<WorkflowWorkbenchCard> trackingCards(
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return new WebListResponse<>(runtimeReadFacade.trackingCards(currentOperatorId(),
                page(normalized.page()), normalized.toQueryRequest()));
    }

    @PostMapping("/workbench/delegation/query")
    public WebListResponse<WorkflowWorkbenchCard> delegationCards(
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return new WebListResponse<>(runtimeReadFacade.delegationCards(currentOperatorId(),
                page(normalized.page()), normalized.toQueryRequest()));
    }

    @PostMapping("/workbench/{board}/stats")
    public WorkflowWorkbenchStats workbenchStats(
            @PathVariable String board,
            @RequestBody(required = false) WorkflowWorkbenchWebRequest request) {
        WorkflowWorkbenchWebRequest normalized = normalizeWorkbenchRequest(request);
        return runtimeReadFacade.workbenchStats(board, currentOperatorId(),
                normalized.toQueryRequest());
    }

    @GetMapping("/task/{taskId}/module-task/prepare")
    public WorkflowModuleTaskProcessBundle prepareModuleTask(@PathVariable String taskId) {
        return moduleTaskRuntimeService.prepare(taskId, currentOperatorId());
    }

    @PostMapping("/task/{taskId}/module-task/check-and-continue")
    @BusinessMutation(actionContextRequired = false)
    public WorkflowModuleTaskContinueResult checkAndContinueModuleTask(
            @PathVariable String taskId,
            @RequestBody(required = false) WorkflowModuleTaskContinueWebRequest request) {
        String operatorId = currentOperatorId();
        if (request != null && request.manualRouteSelections() != null && !request.manualRouteSelections().isEmpty()) {
            return moduleTaskRuntimeService.checkAndContinue(taskId, operatorId, request.reason(),
                    request.selectedRouteKeyOrDirectLinkKey(), request.selectedReason(),
                    request.manualRouteSelections());
        }
        return moduleTaskRuntimeService.checkAndContinue(taskId, operatorId,
                request == null ? null : request.reason(),
                request == null ? null : request.selectedRouteKeyOrDirectLinkKey(),
                request == null ? null : request.selectedReason());
    }

    private WorkflowWorkbenchWebRequest normalizeWorkbenchRequest(WorkflowWorkbenchWebRequest request) {
        return request == null ? WorkflowWorkbenchWebRequest.empty() : request;
    }

    private PageRequest page(WebPageRequest request) {
        WebPageRequest normalized = request == null ? WebPageRequest.DEFAULT : request;
        return PageRequest.of(normalized.pageNum(), normalized.pageSize());
    }

    private String currentOperatorIdOrNull() {
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElse(null);
    }

    private String currentOperatorId() {
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new PlatformException("workflow operator id must not be blank"));
    }

    private WorkflowRejectResubmitMode rejectResubmitMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (WorkflowRejectResubmitMode mode : WorkflowRejectResubmitMode.values()) {
            if (mode.name().equalsIgnoreCase(value) || mode.getCode().equals(value)) {
                return mode;
            }
        }
        throw new PlatformException("unsupported workflow reject resubmit mode: " + value);
    }

    private WorkflowSubmitRequest submitRequest(String moduleAlias,
                                                String recordId,
                                                WorkflowSubmitWebRequest request,
                                                boolean requireOperator) {
        WorkflowSubmitWebRequest normalized = request == null ? WorkflowSubmitWebRequest.empty() : request;
        return WorkflowSubmitRequest.approval(moduleAlias, recordId)
                .withAuthOrgId(CurrentUserContext.currentUser().map(user -> user.organizationId()).orElse(null))
                .withOperator(requireOperator ? currentOperatorId() : currentOperatorIdOrNull())
                .withSelectedRoute(normalized.selectedRouteKeyOrDirectLinkKey(), normalized.selectedReason())
                .withManualRouteSelections(normalized.manualRouteSelections());
    }

    private String jsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

}

record WorkflowOperatorWebRequest(String operatorId) {
}

record WorkflowInstanceActionWebRequest(String operatorId, String reason) {
}

record WorkflowTaskActionWebRequest(String operatorId,
                                    String targetAssigneeId,
                                    WorkflowAddSignSegment addSignSegment,
                                    String rejectResubmitMode,
                                    String reason,
                                    String selectedRouteKey,
                                    String selectedDirectLinkKey,
                                    String selectedReason,
                                    List<WorkflowManualRouteSelection> manualRouteSelections,
                                    JsonNode semanticJson,
                                    JsonNode layoutJson) {
    String selectedRouteKeyOrDirectLinkKey() {
        return selectedRouteKey == null || selectedRouteKey.isBlank() ? selectedDirectLinkKey : selectedRouteKey;
    }
}

record WorkflowSubmitWebRequest(String operatorId,
                                String authOrgId,
                                String selectedRouteKey,
                                String selectedDirectLinkKey,
                                String selectedReason,
                                List<WorkflowManualRouteSelection> manualRouteSelections) {
    static WorkflowSubmitWebRequest empty() {
        return new WorkflowSubmitWebRequest(null, null, null, null, null, List.of());
    }

    String selectedRouteKeyOrDirectLinkKey() {
        return selectedRouteKey == null || selectedRouteKey.isBlank() ? selectedDirectLinkKey : selectedRouteKey;
    }
}

record WorkflowWorkbenchWebRequest(
        String operatorId,
        WebPageRequest page,
        String moduleAlias,
        String recordId,
        String definitionId,
        String workflowVersionId,
        String definitionVersionId,
        WorkflowInstanceStatus instanceStatus,
        String nodeKey,
        WorkflowTaskKind taskKind,
        WorkflowTaskStatus taskStatus,
        WorkflowAssignmentKind assignmentKind,
        WorkflowOvertimeStatus overtimeStatus,
        WorkflowNoticeReadStatus readStatus,
        Instant startedFrom,
        Instant startedTo,
        Instant receivedFrom,
        Instant receivedTo,
        Instant completedFrom,
        Instant completedTo,
        Instant lastOperatedFrom,
        Instant lastOperatedTo,
        Instant dueFrom,
        Instant dueTo,
        Boolean addedByAddSign,
        String addSignSourceNodeKey,
        String submitterUserId,
        List<WorkflowWorkbenchSort> sorts) {
    static WorkflowWorkbenchWebRequest empty() {
        return new WorkflowWorkbenchWebRequest(null, WebPageRequest.DEFAULT, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, List.of());
    }

    WorkflowWorkbenchQueryRequest toQueryRequest() {
        return new WorkflowWorkbenchQueryRequest(moduleAlias, recordId, definitionId, workflowVersionId,
                definitionVersionId, instanceStatus, nodeKey, taskKind, taskStatus, assignmentKind, overtimeStatus,
                readStatus, startedFrom, startedTo, receivedFrom, receivedTo, completedFrom, completedTo,
                lastOperatedFrom, lastOperatedTo, dueFrom, dueTo, addedByAddSign, addSignSourceNodeKey,
                submitterUserId, sorts);
    }
}

record WorkflowModuleTaskContinueWebRequest(String operatorId,
                                            String reason,
                                            String selectedRouteKey,
                                            String selectedDirectLinkKey,
                                            String selectedReason,
                                            List<WorkflowManualRouteSelection> manualRouteSelections) {
    String selectedRouteKeyOrDirectLinkKey() {
        return selectedRouteKey == null || selectedRouteKey.isBlank() ? selectedDirectLinkKey : selectedRouteKey;
    }
}

record WorkflowWorkbenchPageWebRequest(WorkflowWorkbenchWebRequest query, String keyword) { }

record WorkflowManualBranchPlanWebRequest(String taskId, List<WorkflowManualRouteSelection> manualRouteSelections) { }
