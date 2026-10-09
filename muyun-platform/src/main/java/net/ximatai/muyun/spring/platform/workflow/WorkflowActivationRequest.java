package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record WorkflowActivationRequest(
        WorkflowRuntimeGraph graph,
        List<WorkflowActivationTarget> targets,
        Map<String, Set<String>> selectedRouteKeysByBranch,
        Set<String> passedConvergeNodeKeys,
        int maxSteps,
        Map<String, Object> businessFacts
) {
    public WorkflowActivationRequest {
        businessFacts = businessFacts == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(businessFacts));
        targets = targets == null ? List.of() : List.copyOf(targets);
        selectedRouteKeysByBranch = selectedRouteKeysByBranch == null ? Map.of() : Map.copyOf(selectedRouteKeysByBranch);
        passedConvergeNodeKeys = passedConvergeNodeKeys == null ? Set.of() : Set.copyOf(passedConvergeNodeKeys);
        maxSteps = maxSteps <= 0 ? 512 : maxSteps;
    }

    public WorkflowActivationRequest(WorkflowRuntimeGraph graph, List<WorkflowActivationTarget> targets,
                                     Map<String, Set<String>> selections, Set<String> converges, int steps) {
        this(graph, targets, selections, converges, steps, Map.of());
    }

    public static WorkflowActivationRequest from(WorkflowRuntimeGraph graph, String targetNodeKey) {
        return new WorkflowActivationRequest(graph, List.of(WorkflowActivationTarget.of(targetNodeKey)),
                Map.of(), Set.of(), 512);
    }
}
