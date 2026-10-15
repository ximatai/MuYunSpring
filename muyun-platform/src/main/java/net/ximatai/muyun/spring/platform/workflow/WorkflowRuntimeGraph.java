package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowRuntimeGraph(
        Map<String, WorkflowNodeDefinition> nodes,
        Map<String, WorkflowLinkDefinition> links,
        Map<String, List<WorkflowLinkDefinition>> incomingLinks,
        Map<String, List<WorkflowLinkDefinition>> outgoingLinks
) {
    public WorkflowRuntimeGraph {
        nodes = Map.copyOf(nodes == null ? Map.of() : nodes);
        links = Map.copyOf(links == null ? Map.of() : links);
        incomingLinks = Map.copyOf(incomingLinks == null ? Map.of() : incomingLinks);
        outgoingLinks = Map.copyOf(outgoingLinks == null ? Map.of() : outgoingLinks);
    }

    public static WorkflowRuntimeGraph of(List<WorkflowNodeDefinition> nodes, List<WorkflowLinkDefinition> links) {
        Map<String, WorkflowNodeDefinition> nodeMap = new LinkedHashMap<>();
        if (nodes != null) {
            for (WorkflowNodeDefinition node : nodes) {
                if (node == null || node.getNodeKey() == null || node.getNodeKey().isBlank()) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.enter-a-workflow-node-key",
                            "workflow node key must not be blank"));
                }
                if (nodeMap.put(node.getNodeKey(), node) != null) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.duplicate-workflow-node-key",
                            "workflow node key must be unique: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
                }
            }
        }
        Map<String, WorkflowLinkDefinition> routeMap = new LinkedHashMap<>();
        Map<String, List<WorkflowLinkDefinition>> incomingMap = new LinkedHashMap<>();
        Map<String, List<WorkflowLinkDefinition>> outgoingMap = new LinkedHashMap<>();
        if (links != null) {
            for (WorkflowLinkDefinition link : links) {
                if (link == null || link.getSourceNodeKey() == null || link.getTargetNodeKey() == null) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.select-a-source-and-target-for-the-route",
                            "workflow link source and target must not be blank"));
                }
                if (!nodeMap.containsKey(link.getSourceNodeKey())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.route-source-node-not-found",
                            "workflow link source node does not exist: {value1}"), Map.of("value1", String.valueOf(link.getSourceNodeKey())));
                }
                if (!nodeMap.containsKey(link.getTargetNodeKey())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.route-target-node-not-found",
                            "workflow link target node does not exist: {value1}"), Map.of("value1", String.valueOf(link.getTargetNodeKey())));
                }
                if (link.getRouteKey() != null && !link.getRouteKey().isBlank()
                        && routeMap.put(link.getRouteKey(), link) != null) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-runtime-graph.duplicate-workflow-route-key",
                            "workflow route key must be unique: {value1}"), Map.of("value1", String.valueOf(link.getRouteKey())));
                }
                incomingMap.computeIfAbsent(link.getTargetNodeKey(), ignored -> new java.util.ArrayList<>()).add(link);
                outgoingMap.computeIfAbsent(link.getSourceNodeKey(), ignored -> new java.util.ArrayList<>()).add(link);
            }
        }
        incomingMap.replaceAll((ignored, value) -> List.copyOf(value));
        outgoingMap.replaceAll((ignored, value) -> List.copyOf(value));
        return new WorkflowRuntimeGraph(nodeMap, routeMap, incomingMap, outgoingMap);
    }

    public WorkflowNodeDefinition requireNode(String nodeKey) {
        WorkflowNodeDefinition node = nodes.get(nodeKey);
        if (node == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-runtime-graph.workflow-node-not-found",
                    "workflow node does not exist: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
        }
        return node;
    }

    public List<WorkflowLinkDefinition> outgoing(String nodeKey) {
        return outgoingLinks.getOrDefault(nodeKey, List.of());
    }

    public List<WorkflowLinkDefinition> incoming(String nodeKey) {
        return incomingLinks.getOrDefault(nodeKey, List.of());
    }

    public WorkflowLinkDefinition link(String routeKey) {
        return links.get(routeKey);
    }

    public List<WorkflowNodeDefinition> startNodes() {
        return nodes.values().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.START)
                .toList();
    }
}
