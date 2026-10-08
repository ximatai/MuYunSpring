package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.time.Instant;
import java.util.Map;

public record WorkflowHistoryEventView(
        String originType,
        Boolean isAddSignRoute,
        String addSignSourceNodeKey,
        String addSignSourceNodeName,
        String id,
        String instanceId,
        String nodeInstanceId,
        String taskId,
        WorkflowEventType eventType,
        String actionCode,
        String operatorId,
        String operatorTitle,
        String actualProcessUserId,
        String actualProcessUserTitle,
        Boolean processedByDelegation,
        WorkflowAssignmentKind assignmentKind,
        String originalAssigneeId,
        String originalAssigneeTitle,
        String delegatedFromUserId,
        String delegatedFromUserTitle,
        String delegatedToUserId,
        String delegatedToUserTitle,
        Boolean principalCanProcess,
        String delegationPolicyId,
        String delegationSnapshot,
        Boolean taskInvalidated,
        Boolean taskCanceled,
        String message,
        String payloadText,
        Instant occurredAt
) {
    public static final String ORIGIN_TYPE_DEFINITION = "DEFINITION";
    public static final String ORIGIN_TYPE_ADD_SIGN = "ADD_SIGN";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static WorkflowHistoryEventView from(WorkflowEvent event, WorkflowTask task) {
        return from(event, task, Map.of(), Map.of(), Map.of());
    }

    public static WorkflowHistoryEventView from(WorkflowEvent event, WorkflowTask task,
                                                Map<String, WorkflowNodeInstance> nodesById,
                                                Map<String, WorkflowNodeInstance> nodesByKey,
                                                Map<String, WorkflowRouteInstance> routesByIdOrKey) {
        return from(event, task, nodesById, nodesByKey, routesByIdOrKey, Map.of());
    }

    public static WorkflowHistoryEventView from(WorkflowEvent event, WorkflowTask task,
                                                Map<String, WorkflowNodeInstance> nodesById,
                                                Map<String, WorkflowNodeInstance> nodesByKey,
                                                Map<String, WorkflowRouteInstance> routesByIdOrKey,
                                                Map<String, String> userTitles) {
        AddSignExplanation addSign = addSignExplanation(event, nodesById, nodesByKey, routesByIdOrKey);
        String actualProcessUserId = task == null || task.getActualProcessorId() == null
                ? event.getOperatorId()
                : task.getActualProcessorId();
        return new WorkflowHistoryEventView(addSign.originType(), addSign.isAddSignRoute(),
                addSign.sourceNodeKey(), addSign.sourceNodeName(),
                event.getId(), event.getInstanceId(), event.getNodeInstanceId(),
                event.getTaskId(), event.getEventType(), event.getActionCode(), event.getOperatorId(),
                title(event.getOperatorId(), userTitles), actualProcessUserId, title(actualProcessUserId, userTitles),
                task == null ? Boolean.FALSE : WorkflowHistoryTaskViews.processedByDelegation(task),
                task == null ? null : task.getAssignmentKind(),
                task == null ? null : task.getOriginalAssigneeId(),
                title(task == null ? null : task.getOriginalAssigneeId(), userTitles),
                task == null ? null : task.getDelegatedFromUserId(),
                title(task == null ? null : task.getDelegatedFromUserId(), userTitles),
                task == null ? null : task.getDelegatedToUserId(),
                title(task == null ? null : task.getDelegatedToUserId(), userTitles),
                task == null ? null : task.getPrincipalCanProcess(),
                task == null ? null : task.getDelegationPolicyId(),
                task == null ? null : task.getAssignmentSnapshotText(),
                task != null && task.getTaskStatus() == WorkflowTaskStatus.INVALIDATED,
                task != null && task.getTaskStatus() == WorkflowTaskStatus.CANCELED,
                event.getMessage(), event.getPayloadText(), event.getOccurredAt());
    }

    private static AddSignExplanation addSignExplanation(WorkflowEvent event,
                                                         Map<String, WorkflowNodeInstance> nodesById,
                                                         Map<String, WorkflowNodeInstance> nodesByKey,
                                                         Map<String, WorkflowRouteInstance> routesByIdOrKey) {
        JsonNode payload = parsePayload(event == null ? null : event.getPayloadText());
        WorkflowNodeInstance eventNode = event == null ? null : nodesById.get(event.getNodeInstanceId());
        WorkflowRouteInstance route = routeFromPayload(payload, routesByIdOrKey);

        boolean addSignRoute = booleanValue(payload, "isAddSignRoute")
                || booleanValue(payload, "addedByAddSign")
                || Boolean.TRUE.equals(route == null ? null : route.getAddedByAddSign())
                || Boolean.TRUE.equals(eventNode == null ? null : eventNode.getAddedByAddSign());
        String sourceNodeKey = firstText(
                text(payload, "addSignSourceNodeKey"),
                event != null && event.getEventType() == WorkflowEventType.ADD_SIGN ? text(payload, "sourceNodeKey") : null,
                addSignRoute ? text(payload, "sourceNodeKey") : null,
                addSignSourceNodeKey(eventNode),
                addSignSourceNodeKey(route)
        );

        if (sourceNodeKey != null || addSignRoute || (event != null && event.getEventType() == WorkflowEventType.ADD_SIGN)) {
            WorkflowNodeInstance sourceNode = sourceNodeKey == null ? null : nodesByKey.get(sourceNodeKey);
            return new AddSignExplanation(ORIGIN_TYPE_ADD_SIGN, addSignRoute, sourceNodeKey,
                    sourceNode == null ? sourceNodeKey : (sourceNode.getNodeTitle() == null ? sourceNodeKey : sourceNode.getNodeTitle()));
        }
        return new AddSignExplanation(ORIGIN_TYPE_DEFINITION, Boolean.FALSE, null, null);
    }

    private static WorkflowRouteInstance routeFromPayload(JsonNode payload,
                                                          Map<String, WorkflowRouteInstance> routesByIdOrKey) {
        if (routesByIdOrKey == null || routesByIdOrKey.isEmpty()) {
            return null;
        }
        String routeIdentity = firstText(text(payload, "routeId"), text(payload, "routeKey"),
                text(payload, "directLinkKey"), text(payload, "selectedRouteKey"));
        return routeIdentity == null ? null : routesByIdOrKey.get(routeIdentity);
    }

    private static JsonNode parsePayload(String payloadText) {
        if (payloadText == null || payloadText.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(payloadText);
            return parsed == null ? MissingNode.getInstance() : parsed;
        } catch (Exception ignored) {
            return MissingNode.getInstance();
        }
    }

    private static boolean booleanValue(JsonNode payload, String field) {
        JsonNode value = payload == null ? MissingNode.getInstance() : payload.path(field);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText());
        }
        return false;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? MissingNode.getInstance() : payload.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static String addSignSourceNodeKey(WorkflowNodeInstance node) {
        return node != null && Boolean.TRUE.equals(node.getAddedByAddSign())
                ? blankToNull(node.getAddSignSourceNodeKey())
                : null;
    }

    private static String addSignSourceNodeKey(WorkflowRouteInstance route) {
        return route != null && Boolean.TRUE.equals(route.getAddedByAddSign())
                ? blankToNull(route.getAddSignSourceNodeKey())
                : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String text = blankToNull(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String title(String userId, Map<String, String> userTitles) {
        return userId == null || userTitles == null ? null : userTitles.get(userId);
    }

    private record AddSignExplanation(
            String originType,
            Boolean isAddSignRoute,
            String sourceNodeKey,
            String sourceNodeName
    ) {
    }
}
