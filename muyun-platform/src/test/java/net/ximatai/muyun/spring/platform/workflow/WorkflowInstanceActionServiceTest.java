package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowInstanceActionServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); net.ximatai.muyun.spring.common.tenant.TenantContext.setTenantId("tenant-1"); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); net.ximatai.muyun.spring.common.tenant.TenantContext.clear(); }

    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowArchiveService archiveService = mock(WorkflowArchiveService.class);
    private final WorkflowApprovalSummaryWriter summaryWriter = mock(WorkflowApprovalSummaryWriter.class);
    private final WorkflowInstanceActionService service = new WorkflowInstanceActionService(
            instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory, archiveService, Optional.of(summaryWriter));

    @Test
    void shouldRevokeRunningInstanceAndCancelOpenRuntimeObjects() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowInstanceActionService pluginService = serviceWithPlugin(plugin);
        WorkflowInstance instance = instance(true);
        WorkflowTask task = task("task-1");
        WorkflowNodeInstance node = node("node-1");
        WorkflowRouteInstance route = route("route-1", WorkflowRouteStatus.EFFECTIVE);
        stubRuntime(instance, List.of(task), List.of(node), List.of(route));

        WorkflowInstanceActionResult result = pluginService.revoke(new WorkflowInstanceActionRequest(
                "instance-1", "user-1", "withdraw", Instant.parse("2026-06-05T04:00:00Z")));

        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.REVOKED);
        assertThat(result.instance().getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.REVOKED);
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.CANCELED);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.INSTANCE_REVOKED);
        verify(instanceDao).updateByIdAndVersion(instance, 5);
        verify(taskDao).updateByIdAndVersion(task, 3);
        verify(nodeDao).updateByIdAndVersion(node, 2);
        verify(routeDao).updateByIdAndVersion(route, 4);
        verify(eventDao).insert(result.event());
        verify(archiveService).archiveCurrentInstance(instance, WorkflowArchiveReason.RECALLED,
                Instant.parse("2026-06-05T04:00:00Z"));
        verify(summaryWriter).clearCurrent("tenant-1", "sales.contract", "record-1");
        verify(summaryWriter, never()).writeSubmitted(any());
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_REVOKE,
                WorkflowRuntimePluginEventType.AFTER_REVOKE);
    }

    @Test
    void shouldTerminateRunningInstanceAndInvalidateOpenTasks() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowInstanceActionService pluginService = serviceWithPlugin(plugin);
        WorkflowInstance instance = instance(true);
        WorkflowTask task = task("task-1");
        WorkflowNodeInstance node = node("node-1");
        WorkflowRouteInstance route = route("route-1", WorkflowRouteStatus.CANDIDATE);
        stubRuntime(instance, List.of(task), List.of(node), List.of(route));

        WorkflowInstanceActionResult result = pluginService.terminate(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "stop", Instant.parse("2026-06-05T04:00:00Z")));

        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.TERMINATED);
        assertThat(result.instance().getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.TERMINATED);
        assertThat(result.instance().getTerminatedAt()).isEqualTo(Instant.parse("2026-06-05T04:00:00Z"));
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.INVALIDATED);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.CANCELED);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.INSTANCE_TERMINATED);
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_TERMINATE,
                WorkflowRuntimePluginEventType.AFTER_TERMINATE);
        WorkflowRuntimePluginContext before = plugin.contexts().getFirst();
        assertThat(before.terminateMode()).isEqualTo(WorkflowRuntimeTerminateMode.NORMAL);
        assertThat(before.operatorId()).isEqualTo("admin-1");
        assertThat(before.reason()).isEqualTo("stop");
        assertThat(before.nodeKey()).isEqualTo("approve");
        assertThat(before.taskId()).isEqualTo("task-1");
    }

    @Test
    void shouldForceTerminateWithManagementActionCodeAndWithoutArchive() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowInstanceActionService pluginService = serviceWithPlugin(plugin);
        WorkflowInstance instance = instance(true);
        WorkflowTask task = task("task-1");
        WorkflowNodeInstance node = node("node-1");
        WorkflowRouteInstance route = route("route-1", WorkflowRouteStatus.EFFECTIVE);
        stubRuntime(instance, List.of(task), List.of(node), List.of(route));

        WorkflowInstanceActionResult result = pluginService.forceTerminate(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "force stop", Instant.parse("2026-06-05T04:30:00Z")));

        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.TERMINATED);
        assertThat(result.instance().getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.TERMINATED);
        assertThat(result.instance().getLastActionCode()).isEqualTo("forceTerminate");
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.INVALIDATED);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.CANCELED);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.INSTANCE_TERMINATED);
        assertThat(result.event().getActionCode()).isEqualTo("forceTerminate");
        verify(archiveService, never()).archiveCurrentInstance(any(), any(), any());
        verify(summaryWriter).writeSubmitted(any());
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_TERMINATE,
                WorkflowRuntimePluginEventType.AFTER_TERMINATE);
        assertThat(plugin.contexts().getFirst().terminateMode()).isEqualTo(WorkflowRuntimeTerminateMode.FORCE);
    }

    @Test
    void shouldBlockForceTerminateWhenBeforePluginFails() {
        WorkflowRuntimePlugin blocker = new WorkflowRuntimePlugin() {
            @Override
            public String pluginKey() {
                return "blocker";
            }

            @Override
            public void handle(WorkflowRuntimePluginContext context) {
                if (context.eventType() == WorkflowRuntimePluginEventType.BEFORE_TERMINATE
                        && "forceTerminate".equals(context.actionCode())) {
                    throw new PlatformException("blocked by plugin");
                }
            }
        };
        WorkflowInstanceActionService pluginService = serviceWithPlugin(blocker);
        WorkflowInstance instance = instance(true);
        WorkflowTask task = task("task-1");
        WorkflowNodeInstance node = node("node-1");
        WorkflowRouteInstance route = route("route-1", WorkflowRouteStatus.EFFECTIVE);
        stubRuntime(instance, List.of(task), List.of(node), List.of(route));

        assertThatThrownBy(() -> pluginService.forceTerminate(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "force stop", Instant.parse("2026-06-05T04:30:00Z"))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocked by plugin");

        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        verify(instanceDao, never()).updateByIdAndVersion(any(), any());
        verify(taskDao, never()).updateByIdAndVersion(any(), any());
        verify(nodeDao, never()).updateByIdAndVersion(any(), any());
        verify(routeDao, never()).updateByIdAndVersion(any(), any());
        verify(eventDao, never()).insert(any());
        verify(archiveService, never()).archiveCurrentInstance(any(), any(), any());
        verify(summaryWriter, never()).writeSubmitted(any());
        verify(summaryWriter, never()).clearCurrent(any(), any());
    }

    @Test
    void shouldRequireManagementPolicyForForceTerminate() {
        WorkflowActionPolicyService policyService = mock(WorkflowActionPolicyService.class);
        WorkflowInstanceActionService managedService = new WorkflowInstanceActionService(
                instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory, archiveService,
                policyService, Optional.of(summaryWriter));
        WorkflowInstance instance = instance(true);
        stubRuntime(instance, List.of(task("task-1")), List.of(node("node-1")),
                List.of(route("route-1", WorkflowRouteStatus.EFFECTIVE)));

        managedService.forceTerminate(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "force stop", Instant.parse("2026-06-05T04:30:00Z")));

        verify(policyService).requireManagementInstanceAction("forceTerminate", "force stop");
        verify(policyService, never()).requireRuntimeAction(instance, "forceTerminate");
    }

    @Test
    void shouldRequireManagementPolicyForManagementReset() {
        WorkflowActionPolicyService policyService = mock(WorkflowActionPolicyService.class);
        WorkflowInstanceActionService managedService = new WorkflowInstanceActionService(
                instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory, archiveService,
                policyService, Optional.of(summaryWriter));
        WorkflowInstance instance = instance(true);
        stubRuntime(instance, List.of(task("task-1")), List.of(node("node-1")),
                List.of(route("route-1", WorkflowRouteStatus.EFFECTIVE)));

        managedService.managementReset(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "reset", Instant.parse("2026-06-05T05:00:00Z")));

        verify(policyService).requireManagementInstanceAction("reset", "reset");
        verify(policyService, never()).requireRuntimeAction(instance, "reset");
        verify(archiveService).archiveCurrentInstance(instance, WorkflowArchiveReason.RESET,
                Instant.parse("2026-06-05T05:00:00Z"));
    }

    @Test
    void shouldResetInstanceAndArchiveCurrentRuntimeObjects() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowInstanceActionService pluginService = serviceWithPlugin(plugin);
        WorkflowInstance instance = instance(true);
        WorkflowTask task = task("task-1");
        WorkflowNodeInstance node = node("node-1");
        WorkflowRouteInstance route = route("route-1", WorkflowRouteStatus.CANDIDATE);
        stubRuntime(instance, List.of(task), List.of(node), List.of(route));

        WorkflowInstanceActionResult result = pluginService.reset(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", "reset", Instant.parse("2026-06-05T05:00:00Z")));

        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(result.instance().getLastActionCode()).isEqualTo("reset");
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.CANCELED);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.INSTANCE_RESET);
        verify(archiveService).archiveCurrentInstance(instance, WorkflowArchiveReason.RESET,
                Instant.parse("2026-06-05T05:00:00Z"));
        verify(summaryWriter).clearCurrent("tenant-1", "sales.contract", "record-1");
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_RESET,
                WorkflowRuntimePluginEventType.AFTER_RESET);
    }

    @Test
    void shouldRejectClosingNonRunningInstance() {
        WorkflowInstance instance = instance(false);
        instance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
        when(instanceDao.findById("instance-1")).thenReturn(instance);

        assertThatThrownBy(() -> service.revoke(WorkflowInstanceActionRequest.revoke(
                "instance-1", "user-1", "late")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not running");

        verifyNoInteractions(taskDao, nodeDao, routeDao, eventDao);
    }

    @Test
    void shouldRequireReasonForCloseInstanceAction() {
        WorkflowInstance instance = instance(true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);

        assertThatThrownBy(() -> service.terminate(WorkflowInstanceActionRequest.terminate(
                "instance-1", "admin-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reason is required");

        verifyNoInteractions(taskDao, nodeDao, routeDao, eventDao);
    }

    @Test
    void shouldRequireReasonForForceTerminate() {
        WorkflowInstance instance = instance(true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);

        assertThatThrownBy(() -> service.forceTerminate(new WorkflowInstanceActionRequest(
                "instance-1", "admin-1", null, null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reason is required");

        verifyNoInteractions(taskDao, nodeDao, routeDao, eventDao);
    }

    private void stubRuntime(WorkflowInstance instance, List<WorkflowTask> tasks,
                             List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes) {
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.query(any(), any())).thenReturn(tasks);
        when(nodeDao.query(any(), any())).thenReturn(nodes);
        when(routeDao.query(any(), any())).thenReturn(routes);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        tasks.forEach(task -> when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1));
        nodes.forEach(node -> when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1));
        routes.forEach(route -> when(routeDao.updateByIdAndVersion(route, 4)).thenReturn(1));
    }

    private WorkflowInstanceActionService serviceWithPlugin(WorkflowRuntimePlugin plugin) {
        return new WorkflowInstanceActionService(instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory,
                archiveService, new WorkflowActionPolicyService(), Optional.of(summaryWriter),
                new WorkflowRuntimePluginDispatcher(List.of(plugin)));
    }

    private static final class RecordingPlugin implements WorkflowRuntimePlugin {
        private final List<WorkflowRuntimePluginContext> contexts = new ArrayList<>();

        @Override
        public String pluginKey() {
            return "recording";
        }

        @Override
        public void handle(WorkflowRuntimePluginContext context) {
            contexts.add(context);
        }

        List<WorkflowRuntimePluginContext> contexts() {
            return contexts;
        }

        List<WorkflowRuntimePluginEventType> events() {
            return contexts.stream().map(WorkflowRuntimePluginContext::eventType).toList();
        }
    }

    private WorkflowInstance instance(boolean approvalEnabled) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setVersion(5);
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setApprovalEnabled(approvalEnabled);
        instance.setApprovalStatus(approvalEnabled ? WorkflowApprovalStatus.PROCESSING : WorkflowApprovalStatus.NONE);
        instance.setStartedBy("starter-1");
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        instance.setSnapshotText("{}");
        return instance;
    }

    private WorkflowTask task(String id) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setTenantId("tenant-1");
        task.setVersion(3);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        return task;
    }

    private WorkflowNodeInstance node(String id) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setTenantId("tenant-1");
        node.setVersion(2);
        node.setInstanceId("instance-1");
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        node.setNodeKey("approve");
        node.setNodeRunId("approve:1");
        node.setNodeType(WorkflowNodeType.APPROVAL);
        return node;
    }

    private WorkflowRouteInstance route(String id, WorkflowRouteStatus status) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(id);
        route.setTenantId("tenant-1");
        route.setVersion(4);
        route.setInstanceId("instance-1");
        route.setRouteStatus(status);
        route.setRouteKey("route-1");
        route.setRouteRunId("route-1:1");
        route.setSourceNodeKey("start");
        route.setTargetNodeKey("approve");
        return route;
    }
}
