package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkflowInstanceSnapshotFactory {
    private final WorkflowInstanceStateService instanceStateService;
    private final WorkflowRuntimeEventFactory eventFactory;

    public WorkflowInstanceSnapshotFactory(WorkflowInstanceStateService instanceStateService,
                                           WorkflowRuntimeEventFactory eventFactory) {
        this.instanceStateService = instanceStateService;
        this.eventFactory = eventFactory;
    }

    public WorkflowInstanceSnapshot build(WorkflowDefinition definition,
                                          WorkflowVersion version,
                                          List<WorkflowNodeDefinition> nodeDefinitions,
                                          List<WorkflowLinkDefinition> linkDefinitions,
                                          String recordId,
                                          String startedBy,
                                          Instant startedAt) {
        return build(definition, version, nodeDefinitions, linkDefinitions, recordId, null, startedBy, startedAt);
    }

    public WorkflowInstanceSnapshot build(WorkflowDefinition definition,
                                          WorkflowVersion version,
                                          List<WorkflowNodeDefinition> nodeDefinitions,
                                          List<WorkflowLinkDefinition> linkDefinitions,
                                          String recordId,
                                          String authOrgId,
                                          String startedBy,
                                          Instant startedAt) {
        String snapshotText = version == null ? null : version.getSnapshotText();
        if (snapshotText == null || snapshotText.isBlank()) {
            snapshotText = "{}";
        }
        WorkflowInstance instance = instanceStateService.startInstance(definition, version, recordId, authOrgId,
                startedBy, startedAt, snapshotText);
        instance.setId(Ids.newId());
        List<WorkflowNodeInstance> nodes = createNodeSnapshots(instance, nodeDefinitions);
        List<WorkflowRouteInstance> routes = createRouteSnapshots(instance, nodeDefinitions, linkDefinitions);
        List<WorkflowEvent> events = List.of(eventFactory.instanceStarted(instance, startedBy, startedAt));
        return new WorkflowInstanceSnapshot(instance, nodes, routes, events);
    }

    private List<WorkflowNodeInstance> createNodeSnapshots(WorkflowInstance instance,
                                                           List<WorkflowNodeDefinition> nodeDefinitions) {
        if (nodeDefinitions == null || nodeDefinitions.isEmpty()) {
            throw new PlatformException("workflow snapshot must contain nodes");
        }
        List<WorkflowNodeInstance> result = new ArrayList<>();
        for (WorkflowNodeDefinition definition : nodeDefinitions) {
            WorkflowNodeInstance node = new WorkflowNodeInstance();
            node.setId(Ids.newId());
            node.setTenantId(instance.getTenantId());
            node.setInstanceId(instance.getId());
            node.setNodeKey(requireText(definition.getNodeKey(), "workflow node key must not be blank"));
            node.setNodeTitle(firstText(definition.getTitle(), definition.getNodeKey()));
            node.setNodeRunId(node.getNodeKey() + ":1");
            node.setNodeType(definition.getNodeType());
            node.setNodeStatus(WorkflowNodeStatus.WAITING);
            node.setApprovalMode(definition.getApprovalMode());
            node.setApprovalRatio(definition.getApprovalRatio());
            node.setMilestoneType(definition.getMilestoneType());
            node.setConvergeMode(definition.getConvergeMode());
            node.setConvergeRatio(definition.getConvergeRatio());
            node.setRouteMode(definition.getRouteMode());
            node.setSelectorNodeKey(definition.getSelectorNodeKey());
            node.setConvergeNodeKey(definition.getConvergeNodeKey());
            node.setRequireManualSelectionReason(definition.getRequireManualSelectionReason());
            node.setTaskDefinitionId(definition.getTaskDefinitionId());
            node.setParticipantPolicyText(definition.getParticipantPolicyText());
            node.setAllowReject(definition.getAllowReject());
            node.setAutoApproveSameUser(definition.getAutoApproveSameUser());
            node.setRequireRejectReason(definition.getRequireRejectReason());
            node.setAllowRejectReturnToMe(definition.getAllowRejectReturnToMe());
            node.setAllowRollback(definition.getAllowRollback());
            node.setRequireRollbackReason(definition.getRequireRollbackReason());
            node.setAllowTerminate(definition.getAllowTerminate());
            node.setRequireTerminateReason(definition.getRequireTerminateReason());
            node.setAllowAddSign(definition.getAllowAddSign());
            node.setWarningDurationMinutes(definition.getWarningDurationMinutes());
            node.setOvertimeDurationMinutes(definition.getOvertimeDurationMinutes());
            node.setNodeSnapshotText(definition.getNodeConfigText());
            result.add(node);
        }
        return result;
    }

    private List<WorkflowRouteInstance> createRouteSnapshots(WorkflowInstance instance,
                                                             List<WorkflowNodeDefinition> nodeDefinitions,
                                                             List<WorkflowLinkDefinition> linkDefinitions) {
        if (linkDefinitions == null || linkDefinitions.isEmpty()) {
            return List.of();
        }
        List<WorkflowRouteInstance> result = new ArrayList<>();
        for (WorkflowLinkDefinition definition : linkDefinitions) {
            WorkflowRouteInstance route = new WorkflowRouteInstance();
            route.setId(Ids.newId());
            route.setTenantId(instance.getTenantId());
            route.setInstanceId(instance.getId());
            route.setRouteKey(requireText(definition.getRouteKey(), "workflow route key must not be blank"));
            route.setRouteRunId(route.getRouteKey() + ":1");
            route.setSourceNodeKey(requireText(definition.getSourceNodeKey(), "workflow source node key must not be blank"));
            route.setTargetNodeKey(requireText(definition.getTargetNodeKey(), "workflow target node key must not be blank"));
            route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
            route.setDefaultRoute(definition.getDefaultRoute());
            route.setConditionExpression(definition.getConditionExpression());
            nodeDefinitions.stream().filter(node -> node.getNodeKey().equals(route.getSourceNodeKey()))
                    .filter(node -> node.getNodeType() == WorkflowNodeType.BRANCH).findFirst().ifPresent(branch -> {
                        route.setBranchNodeKey(branch.getNodeKey());
                        route.setBranchRunId(branch.getNodeKey() + ":1");
                        route.setConvergeNodeKey(branch.getConvergeNodeKey());
                        route.setConvergeRunId(branch.getConvergeNodeKey() + ":1");
                        route.setPathRouteId(route.getId());
                    });
            result.add(route);
        }
        return result;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second;
    }
}
