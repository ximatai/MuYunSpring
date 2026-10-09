package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class WorkflowManualBranchSelectorResolver {
    static final String SELECTOR_NOT_FOUND = "SELECTOR_NOT_FOUND";
    static final String SELECTOR_NOT_OPERATOR = "SELECTOR_NOT_OPERATOR";
    static final String SELECTOR_NOT_PROCESSED = "SELECTOR_NOT_PROCESSED";
    static final String SELECTOR_UNSUPPORTED = "SELECTOR_UNSUPPORTED";

    SelectorResolution resolve(WorkflowInstance instance,
                               List<WorkflowNodeInstance> nodes,
                               List<WorkflowTask> tasks,
                               String selectorNodeKey,
                               String fallbackSelectorNodeKey,
                               String operatorId) {
        String effectiveSelectorNodeKey = firstText(selectorNodeKey, fallbackSelectorNodeKey);
        if (effectiveSelectorNodeKey == null) {
            return SelectorResolution.unresolved(null, null, SELECTOR_NOT_FOUND);
        }
        String resolvedUserId;
        WorkflowNodeInstance selectorNode = nodesByKey(nodes).get(effectiveSelectorNodeKey);
        if (selectorNode != null && selectorNode.getNodeType() == WorkflowNodeType.START
                || selectorNode == null && "START".equalsIgnoreCase(effectiveSelectorNodeKey)) {
            resolvedUserId = firstText(instance == null ? null : instance.getStartedBy(), null);
        } else {
            if (selectorNode == null) {
                return SelectorResolution.unresolved(effectiveSelectorNodeKey, null, SELECTOR_NOT_FOUND);
            }
            if (selectorNode.getNodeType() != WorkflowNodeType.APPROVAL
                    && selectorNode.getNodeType() != WorkflowNodeType.TASK) {
                return SelectorResolution.unresolved(effectiveSelectorNodeKey, null, SELECTOR_UNSUPPORTED);
            }
            resolvedUserId = resolvedTaskUserId(selectorNode, tasks);
        }
        if (resolvedUserId == null) {
            return SelectorResolution.unresolved(effectiveSelectorNodeKey, null, SELECTOR_NOT_PROCESSED);
        }
        if (!resolvedUserId.equals(firstText(operatorId, null))) {
            return SelectorResolution.unresolved(effectiveSelectorNodeKey, resolvedUserId, SELECTOR_NOT_OPERATOR);
        }
        return SelectorResolution.selectable(effectiveSelectorNodeKey, resolvedUserId);
    }

    private String resolvedTaskUserId(WorkflowNodeInstance selectorNode, List<WorkflowTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        List<WorkflowTask> selectorTasks = tasks.stream()
                .filter(task -> selectorNode.getId() != null && selectorNode.getId().equals(task.getNodeInstanceId()))
                .toList();
        return selectorTasks.stream()
                .filter(task -> firstText(task.getActualProcessorId(), null) != null)
                .max(taskSort())
                .map(WorkflowTask::getActualProcessorId)
                .orElse(null);
    }

    private Comparator<WorkflowTask> taskSort() {
        return Comparator
                .comparing(WorkflowManualBranchSelectorResolver::completedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(WorkflowTask::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(WorkflowTask::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    private static Instant completedAt(WorkflowTask task) {
        return task == null ? null : task.getCompletedAt();
    }

    private Map<String, WorkflowNodeInstance> nodesByKey(List<WorkflowNodeInstance> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        return nodes.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    record SelectorResolution(
            String selectorNodeKey,
            String resolvedUserId,
            boolean selectable,
            String unselectableReason
    ) {
        static SelectorResolution selectable(String selectorNodeKey, String resolvedUserId) {
            return new SelectorResolution(selectorNodeKey, resolvedUserId, true, null);
        }

        static SelectorResolution unresolved(String selectorNodeKey, String resolvedUserId, String reason) {
            return new SelectorResolution(selectorNodeKey, resolvedUserId, false, reason);
        }
    }
}
