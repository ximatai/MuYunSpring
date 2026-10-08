package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.platform.module.ModuleActionBindingType;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class DynamicWorkflowActionExecutor implements DynamicActionExecutor {
    public static final String EXECUTOR_KEY = "platform.workflow";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ACTION_SUBMIT_APPROVAL = "submitApproval";
    private static final String ACTION_SUBMIT_WORKFLOW = "submitWorkflow";
    private static final String ACTION_TASK_ACTION = "taskAction";
    private static final String ACTION_AVAILABLE_TASK_ACTIONS = "availableTaskActions";
    private static final List<String> INSTANCE_ACTION_CODES = List.of("revoke", "terminate", "reset");

    private final WorkflowModuleSubmitService submitService;
    private final WorkflowTaskActionFacade taskActionFacade;
    private final WorkflowInstanceActionFacade instanceActionFacade;
    private final PlatformModuleActionService actionService;

    public DynamicWorkflowActionExecutor(WorkflowModuleSubmitService submitService,
                                         WorkflowTaskActionFacade taskActionFacade,
                                         WorkflowInstanceActionFacade instanceActionFacade,
                                         PlatformModuleActionService actionService) {
        this.submitService = submitService;
        this.taskActionFacade = taskActionFacade;
        this.instanceActionFacade = instanceActionFacade;
        this.actionService = actionService;
    }

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        String workflowAction = requireText(context == null ? null : context.actionCode(),
                "workflow action code must not be blank");
        return switch (workflowAction) {
            case ACTION_SUBMIT_APPROVAL -> DynamicActionResultBody.refreshed(
                    submitApproval(context, request));
            case ACTION_SUBMIT_WORKFLOW -> DynamicActionResultBody.refreshed(
                    submitWorkflow(context, request, workflowDefinitionAlias(context, request)));
            case ACTION_TASK_ACTION -> DynamicActionResultBody.refreshed(taskActionFacade.execute(
                    requireText(payload(request, "taskActionCode"), "workflow task action code must not be blank"),
                    taskRequest(context, request)));
            case ACTION_AVAILABLE_TASK_ACTIONS -> DynamicActionResultBody.of(taskActionFacade.availableActions(
                    requireText(payload(request, "taskId"), "workflow task id must not be blank"),
                    operatorId(context)));
            default -> executeBoundWorkflowAction(context, request, workflowAction);
        };
    }

    private DynamicActionResultBody executeBoundWorkflowAction(DynamicActionExecutionContext context,
                                                              DynamicActionExecutionRequest request,
                                                              String workflowAction) {
        String definitionAlias = boundWorkflowDefinitionAlias(context);
        if (definitionAlias != null) {
            return DynamicActionResultBody.refreshed(
                    submitWorkflow(context, request, definitionAlias));
        }
        if (!WorkflowActionPolicyService.RUNTIME_RECORD_ACTION_CODES.contains(workflowAction)) {
            throw new PlatformException("unsupported dynamic workflow action: " + workflowAction);
        }
        if (INSTANCE_ACTION_CODES.contains(workflowAction)) {
            return DynamicActionResultBody.refreshed(instanceActionFacade.execute(workflowAction,
                    instanceRequest(context, request)));
        }
        return DynamicActionResultBody.refreshed(taskActionFacade.execute(workflowAction, taskRequest(context, request)));
    }

    private WorkflowSubmitResult submitApproval(DynamicActionExecutionContext context,
                                                DynamicActionExecutionRequest request) {
        List<WorkflowManualRouteSelection> selections = manualRouteSelections(request);
        if (!selections.isEmpty()) {
            return submitService.submitApproval(moduleAlias(context), recordId(context, request), selections);
        }
        return submitService.submitApproval(moduleAlias(context), recordId(context, request),
                selectedRouteKey(request), text(payload(request, "selectedReason"), null));
    }

    private WorkflowSubmitResult submitWorkflow(DynamicActionExecutionContext context,
                                                DynamicActionExecutionRequest request,
                                                String definitionAlias) {
        List<WorkflowManualRouteSelection> selections = manualRouteSelections(request);
        if (!selections.isEmpty()) {
            return submitService.submitWorkflow(moduleAlias(context), recordId(context, request), definitionAlias,
                    selections);
        }
        return submitService.submitWorkflow(moduleAlias(context), recordId(context, request), definitionAlias,
                selectedRouteKey(request), text(payload(request, "selectedReason"), null));
    }

    private String workflowDefinitionAlias(DynamicActionExecutionContext context,
                                           DynamicActionExecutionRequest request) {
        String definitionAlias = text(payload(request, "definitionAlias"), null);
        if (definitionAlias != null) {
            return definitionAlias;
        }
        return requireText(boundWorkflowDefinitionAlias(context), "workflow definition alias must not be blank");
    }

    private String boundWorkflowDefinitionAlias(DynamicActionExecutionContext context) {
        String moduleAlias = text(context == null ? null : context.moduleAlias(), null);
        String actionCode = text(context == null ? null : context.actionCode(), null);
        if (moduleAlias == null || actionCode == null) {
            return null;
        }
        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode(moduleAlias, actionCode);
        if (action == null || action.getBindingType() != ModuleActionBindingType.WORKFLOW_DEFINITION) {
            return null;
        }
        return text(action.getBindingAlias(), null);
    }

    private WorkflowTaskActionRequest taskRequest(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        return WorkflowTaskActionRequest.builder(
                        requireText(payload(request, "taskId"), "workflow task id must not be blank"),
                        operatorId(context))
                .targetAssigneeId(text(payload(request, "targetAssigneeId"), null))
                .addSignSegment(addSignSegment(payload(request, "addSignSegment")))
                .rejectResubmitMode(rejectResubmitMode(payload(request, "rejectResubmitMode")))
                .reason(text(payload(request, "reason"), null))
                .operatedAt(operatedAt(payload(request, "operatedAt")))
                .selectedRoute(selectedRouteKey(request), text(payload(request, "selectedReason"), null))
                .manualRouteSelections(manualRouteSelections(request))
                .designerSnapshot(jsonText(payload(request, "semanticJson")),
                        jsonText(payload(request, "layoutJson")))
                .build();
    }

    private WorkflowInstanceActionRequest instanceRequest(DynamicActionExecutionContext context,
                                                          DynamicActionExecutionRequest request) {
        return new WorkflowInstanceActionRequest(
                requireText(payload(request, "instanceId"), "workflow instance id must not be blank"),
                operatorId(context),
                text(payload(request, "reason"), null),
                operatedAt(payload(request, "operatedAt")));
    }

    private WorkflowAddSignSegment addSignSegment(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof WorkflowAddSignSegment segment) {
            return segment;
        }
        return OBJECT_MAPPER.convertValue(value, WorkflowAddSignSegment.class);
    }

    private String jsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node.isNull() ? null : node.isTextual() ? node.asText() : node.toString();
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new PlatformException("workflow json payload must be serializable");
        }
    }

    private List<WorkflowManualRouteSelection> manualRouteSelections(DynamicActionExecutionRequest request) {
        Object value = payload(request, "manualRouteSelections");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(this::manualRouteSelection)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private WorkflowManualRouteSelection manualRouteSelection(Object value) {
        if (value instanceof WorkflowManualRouteSelection selection) {
            return selection;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new PlatformException("workflow manual route selection must be object");
        }
        return new WorkflowManualRouteSelection(
                text(((Map<String, Object>) map).get("branchNodeKey"), null),
                text(((Map<String, Object>) map).get("routeKey"), null),
                text(((Map<String, Object>) map).get("selectedReason"), null)
        );
    }

    private String selectedRouteKey(DynamicActionExecutionRequest request) {
        String routeKey = text(payload(request, "selectedRouteKey"), null);
        return routeKey == null ? text(payload(request, "selectedDirectLinkKey"), null) : routeKey;
    }

    private WorkflowRejectResubmitMode rejectResubmitMode(Object value) {
        String mode = text(value, null);
        if (mode == null) {
            return null;
        }
        for (WorkflowRejectResubmitMode candidate : WorkflowRejectResubmitMode.values()) {
            if (candidate.name().equalsIgnoreCase(mode) || candidate.getCode().equalsIgnoreCase(mode)) {
                return candidate;
            }
        }
        throw new PlatformException("unsupported workflow reject resubmit mode: " + mode);
    }

    private Instant operatedAt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(requireText(value, "workflow operatedAt must not be blank"));
    }

    private String moduleAlias(DynamicActionExecutionContext context) {
        return requireText(context == null ? null : context.moduleAlias(), "workflow module alias must not be blank");
    }

    private String recordId(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        String recordId = text(request == null ? null : request.recordId(), null);
        if (recordId == null && request != null && request.record() != null) {
            recordId = text(request.record().getId(), null);
        }
        if (recordId == null && context != null) {
            recordId = text(context.recordId(), null);
        }
        return requireText(recordId, "workflow record id must not be blank");
    }

    private String operatorId(DynamicActionExecutionContext context) {
        if (context != null && text(context.operatorId(), null) != null) {
            return context.operatorId();
        }
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .orElseThrow(() -> new PlatformException("authenticated workflow operator is required"));
    }

    private Object payload(DynamicActionExecutionRequest request, String key) {
        Map<String, Object> payload = request == null ? Map.of() : request.payload();
        return payload.get(key);
    }

    private String requireText(Object value, String message) {
        String text = text(value, null);
        if (text == null) {
            throw new PlatformException(message);
        }
        return text;
    }

    private String text(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }
}
