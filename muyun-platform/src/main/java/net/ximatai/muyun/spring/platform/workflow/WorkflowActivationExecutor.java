package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executes graph traversal and branch convergence against one instance's frozen topology. */
final class WorkflowActivationExecutor {
    private final WorkflowRuntimeActivationService activation;
    private final WorkflowNodeInstanceStateService nodes;
    private final WorkflowRouteInstanceStateService routes;
    private final WorkflowRouteRuntimeService pathRuntime;

    WorkflowActivationExecutor(WorkflowRuntimeActivationService activation, WorkflowNodeInstanceStateService nodes,
                               WorkflowRouteInstanceStateService routes, WorkflowRouteRuntimeService pathRuntime) {
        this.activation = activation; this.nodes = nodes; this.routes = routes; this.pathRuntime = pathRuntime;
    }

    WorkflowActivationResult execute(WorkflowActivationRequest request, List<WorkflowNodeInstance> nodeRuns,
                                     List<WorkflowRouteInstance> edgeRuns, String operator, Instant now) {
        var initial = activation.activate(request);
        var seedRoutes = request.targets().stream().map(WorkflowActivationTarget::routeInstanceId)
                .filter(Objects::nonNull).flatMap(id -> edgeRuns.stream().filter(edge -> id.equals(edge.getId())))
                .map(WorkflowRouteInstance::getRouteKey).toList();
        var result = new WorkflowActivationResult(initial.activatedNodeKeys(), union(seedRoutes, initial.traversedRouteKeys()),
                initial.blockingApprovalNodeKeys(), initial.blockingTaskNodeKeys(), initial.waitingConvergeNodeKeys(),
                initial.reachedMilestones(), initial.completed());
        Set<String> passed = new HashSet<>(request.passedConvergeNodeKeys());
        Set<String> arrivedEdges = new HashSet<>();
        for (int iteration = 0; iteration <= request.graph().nodes().size(); iteration++) {
            nodes.applyActivation(nodeRuns, result, now);
            routes.applyActivation(edgeRuns, result, operator, now);
            assignPaths(request.graph(), result.traversedRouteKeys(), edgeRuns);
            excludeUnselectedPaths(request.graph(), result, edgeRuns, operator, now);
            recordBranchDecisions(request, result, edgeRuns, operator, now);
            List<String> newlyPassed = new ArrayList<>();
            for (var edge : edgeRuns) {
                if (edge.getRouteStatus() != WorkflowRouteStatus.EFFECTIVE
                        || !result.traversedRouteKeys().contains(edge.getRouteKey()) || !arrivedEdges.add(edge.getId())) continue;
                var converge = request.graph().requireNode(edge.getTargetNodeKey());
                if (converge.getNodeType() != WorkflowNodeType.CONVERGE || passed.contains(converge.getNodeKey())) continue;
                var path = edge.getPathRouteId() == null ? null : edgeRuns.stream()
                        .filter(item -> edge.getPathRouteId().equals(item.getId())).findFirst().orElse(null);
                WorkflowConvergeDecision decision;
                if (path != null && converge.getNodeKey().equals(path.getConvergeNodeKey())) {
                    decision = pathRuntime.handleConvergeArrival(path, edgeRuns.stream()
                            .filter(item -> Objects.equals(item.getId(), item.getPathRouteId())).toList(),
                            converge.getConvergeMode(), converge.getConvergeRatio(), now);
                } else {
                    decision = pathRuntime.handleConvergeArrival(edge, edgeRuns.stream()
                            .filter(item -> converge.getNodeKey().equals(item.getTargetNodeKey())).toList(),
                            converge.getConvergeMode(), converge.getConvergeRatio(), now);
                }
                edge.setArrivedAt(now);
                if (decision.passed()) {
                    passed.add(converge.getNodeKey());
                    newlyPassed.add(converge.getNodeKey());
                }
            }
            result = excludeDroppedPathNodes(result, nodeRuns, edgeRuns, now);
            if (newlyPassed.isEmpty()) return result;
            var continuation = activation.activate(new WorkflowActivationRequest(request.graph(), newlyPassed.stream()
                    .map(WorkflowActivationTarget::of).toList(), request.selectedRouteKeysByBranch(), passed,
                    request.maxSteps(), request.businessFacts()));
            result = merge(result, continuation, passed);
        }
        throw new net.ximatai.muyun.spring.common.exception.PlatformException("workflow convergence exceeded graph limit");
    }

    private WorkflowActivationResult excludeDroppedPathNodes(WorkflowActivationResult result,
            List<WorkflowNodeInstance> nodeRuns, List<WorkflowRouteInstance> edges, Instant now) {
        var byId = edges.stream().collect(java.util.stream.Collectors.toMap(WorkflowRouteInstance::getId,
                java.util.function.Function.identity()));
        Set<String> skipped = new HashSet<>();
        for (String key : result.activatedNodeKeys()) {
            var incoming = edges.stream().filter(edge -> key.equals(edge.getTargetNodeKey())).toList();
            boolean dropped = incoming.stream().anyMatch(edge -> belongsToDroppedPath(edge, byId));
            boolean surviving = incoming.stream().anyMatch(edge -> !belongsToDroppedPath(edge, byId)
                    && (edge.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE
                    || edge.getRouteStatus() == WorkflowRouteStatus.CLOSED));
            if (!dropped || surviving) continue;
            skipped.add(key);
            nodeRuns.stream().filter(node -> key.equals(node.getNodeKey()))
                    .filter(node -> node.getNodeStatus() == WorkflowNodeStatus.ACTIVE
                            || node.getNodeStatus() == WorkflowNodeStatus.WAITING)
                    .forEach(node -> { node.setNodeStatus(WorkflowNodeStatus.SKIPPED); node.setCompletedAt(now); });
        }
        if (skipped.isEmpty()) return result;
        return new WorkflowActivationResult(result.activatedNodeKeys().stream().filter(key -> !skipped.contains(key)).toList(),
                result.traversedRouteKeys(), result.blockingApprovalNodeKeys().stream().filter(key -> !skipped.contains(key)).toList(),
                result.blockingTaskNodeKeys().stream().filter(key -> !skipped.contains(key)).toList(),
                result.waitingConvergeNodeKeys().stream().filter(key -> !skipped.contains(key)).toList(),
                result.reachedMilestones(), result.completed());
    }

    private boolean belongsToDroppedPath(WorkflowRouteInstance edge, java.util.Map<String, WorkflowRouteInstance> edges) {
        Set<String> visited = new HashSet<>();
        if (edge.getRouteStatus() == WorkflowRouteStatus.DROPPED) return true;
        String path = edge.getPathRouteId();
        while (path != null && visited.add(path)) {
            var owner = edges.get(path);
            if (owner == null) return false;
            if (owner.getRouteStatus() == WorkflowRouteStatus.DROPPED) return true;
            path = owner.getParentRouteId();
        }
        return false;
    }

    private void assignPaths(WorkflowRuntimeGraph graph, List<String> traversed, List<WorkflowRouteInstance> runs) {
        var byId = runs.stream().collect(java.util.stream.Collectors.toMap(WorkflowRouteInstance::getId,
                java.util.function.Function.identity()));
        for (String key : traversed) {
            var edge = runs.stream().filter(item -> key.equals(item.getRouteKey())).findFirst().orElseThrow();
            if (belongsToDroppedPath(edge, byId)) continue;
            var incoming = runs.stream().filter(item -> edge.getSourceNodeKey().equals(item.getTargetNodeKey()))
                    .filter(item -> item.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE || item.getRouteStatus() == WorkflowRouteStatus.CLOSED)
                    .findFirst().orElse(null);
            String parent = incoming == null ? null : incoming.getPathRouteId();
            var source = graph.requireNode(edge.getSourceNodeKey());
            if (source.getNodeType() == WorkflowNodeType.CONVERGE && parent != null) {
                var path = runs.stream().filter(item -> Objects.equals(item.getId(), incoming.getPathRouteId())).findFirst().orElse(null);
                if (path != null && source.getNodeKey().equals(path.getConvergeNodeKey())) parent = path.getParentRouteId();
            }
            if (source.getNodeType() == WorkflowNodeType.BRANCH) {
                edge.setPathRouteId(edge.getId());
                edge.setParentRouteId(parent);
                edge.setBranchNodeKey(source.getNodeKey());
                edge.setBranchRunId(source.getNodeKey() + ":1");
                edge.setConvergeNodeKey(source.getConvergeNodeKey());
                edge.setConvergeRunId(source.getConvergeNodeKey() + ":1");
            } else {
                edge.setPathRouteId(parent);
                if (parent != null) {
                    var owner = runs.stream().filter(item -> Objects.equals(item.getId(), edge.getPathRouteId())).findFirst().orElseThrow();
                    edge.setBranchNodeKey(owner.getBranchNodeKey()); edge.setBranchRunId(owner.getBranchRunId());
                    edge.setConvergeNodeKey(owner.getConvergeNodeKey()); edge.setConvergeRunId(owner.getConvergeRunId());
                    edge.setParentRouteId(owner.getParentRouteId());
                }
            }
        }
    }

    private void excludeUnselectedPaths(WorkflowRuntimeGraph graph, WorkflowActivationResult result,
                                       List<WorkflowRouteInstance> edges, String operator, Instant now) {
        for (String key : result.activatedNodeKeys()) {
            var node = graph.requireNode(key);
            if (node.getNodeType() != WorkflowNodeType.BRANCH) continue;
            for (var edge : edges) {
                if (key.equals(edge.getSourceNodeKey()) && edge.getRouteStatus() == WorkflowRouteStatus.CANDIDATE) {
                    pathRuntime.ineffectiveRoute(edge, node.getRouteMode() == WorkflowRouteMode.MANUAL
                            ? WorkflowRouteReason.MANUAL_UNSELECTED : WorkflowRouteReason.CONDITION_UNMATCHED, operator, now);
                }
            }
        }
    }

    private void recordBranchDecisions(WorkflowActivationRequest request, WorkflowActivationResult result,
                                       List<WorkflowRouteInstance> edges, String operator, Instant now) {
        var formulas = new net.ximatai.muyun.spring.common.formula.FormulaEngine();
        for (String key : result.activatedNodeKeys()) {
            var node = request.graph().requireNode(key);
            if (node.getNodeType() != WorkflowNodeType.BRANCH) continue;
            for (var edge : edges) {
                if (!key.equals(edge.getSourceNodeKey())
                        || edge.getRouteStatus() == WorkflowRouteStatus.CLOSED
                        || edge.getRouteStatus() == WorkflowRouteStatus.DROPPED) continue;
                Boolean matched;
                try {
                    matched = node.getRouteMode() != WorkflowRouteMode.MANUAL
                            ? !Boolean.TRUE.equals(edge.getDefaultRoute()) && edge.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE
                            : !Boolean.TRUE.equals(edge.getDefaultRoute())
                            && (edge.getConditionExpression() == null || edge.getConditionExpression().isBlank()
                            || WorkflowConditionService.requireBoolean(formulas.evaluateValue(edge.getConditionExpression(),
                            net.ximatai.muyun.spring.common.formula.FormulaRuntimeData.of(request.businessFacts()))));
                } catch (net.ximatai.muyun.spring.common.formula.FormulaEvaluationException
                         | net.ximatai.muyun.spring.common.exception.PlatformException invalid) {
                    if (node.getRouteMode() != WorkflowRouteMode.MANUAL) throw invalid;
                    matched = null;
                }
                if (edge.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE) {
                    pathRuntime.effectiveRoute(edge, node.getRouteMode() == WorkflowRouteMode.MANUAL
                            ? WorkflowRouteReason.MANUAL_SELECTED : Boolean.TRUE.equals(edge.getDefaultRoute())
                            ? WorkflowRouteReason.DEFAULT_SELECTED : WorkflowRouteReason.CONDITION_MATCHED, operator, now);
                }
                // Condition advice and the user's selection are distinct frozen facts.
                edge.setConditionMatched(matched);
            }
        }
    }

    private WorkflowActivationResult merge(WorkflowActivationResult left, WorkflowActivationResult right, Set<String> passed) {
        return new WorkflowActivationResult(union(left.activatedNodeKeys(), right.activatedNodeKeys()),
                union(left.traversedRouteKeys(), right.traversedRouteKeys()),
                union(left.blockingApprovalNodeKeys(), right.blockingApprovalNodeKeys()),
                union(left.blockingTaskNodeKeys(), right.blockingTaskNodeKeys()),
                union(left.waitingConvergeNodeKeys(), right.waitingConvergeNodeKeys()).stream().filter(key -> !passed.contains(key)).toList(),
                union(left.reachedMilestones(), right.reachedMilestones()), left.completed() || right.completed());
    }

    private <T> List<T> union(List<T> left, List<T> right) { var items = new LinkedHashSet<>(left); items.addAll(right); return List.copyOf(items); }
}
