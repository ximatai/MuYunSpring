package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WorkflowTaskActionServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); net.ximatai.muyun.spring.common.tenant.TenantContext.setTenantId("tenant-1"); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); net.ximatai.muyun.spring.common.tenant.TenantContext.clear(); }

    private final WorkflowModuleTaskEvaluator taskEvaluator = mock(WorkflowModuleTaskEvaluator.class);
    private final WorkflowBusinessTaskResolver taskSpecifications = mock(WorkflowBusinessTaskResolver.class);
    private final WorkflowTaskCheckResultDao checkResultDao = mock(WorkflowTaskCheckResultDao.class);

    @org.junit.jupiter.api.BeforeEach
    void configureBusinessCompletionPolicy() {
        var definition = new WorkflowTaskDefinition(); definition.setManualConfirm(true);
        when(taskSpecifications.resolve(any())).thenReturn(new WorkflowBusinessTaskSpec(definition, List.of(), List.of()));
        when(taskEvaluator.evaluate(any(), any(), any(), any())).thenReturn(WorkflowModuleTaskEvaluation.manualConfirm(List.of()));
        when(progressionService.advanceFromNode(anyString(), anyString(), anyString(), any(), any(), any(), anyList()))
                .thenAnswer(call -> WorkflowProgressionResult.empty(instanceDao.findById(call.getArgument(0))));
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowApprovalTaskPolicyService approvalTaskPolicyService = new WorkflowApprovalTaskPolicyService();
    private final WorkflowActionPolicyService actionPolicyService = new WorkflowActionPolicyService();
    private final WorkflowRuntimeProgressionService progressionService = mock(WorkflowRuntimeProgressionService.class);
    private final WorkflowAutomaticApprovalService automaticApprovals = mock(WorkflowAutomaticApprovalService.class);
    private final WorkflowTaskActionService service = new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                actionPolicyService,
                progressionService,
                Optional.empty(),
                mock(WorkflowDelegationService.class),
                null,
                null,
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(automaticApprovals));

    @Test
    void constructionRequiresTheLazyAutomaticApprovalProvider() {
        assertThatThrownBy(() -> new WorkflowTaskActionService(taskDao, instanceDao, nodeDao, routeDao, eventDao,
                eventFactory, approvalTaskPolicyService, actionPolicyService, progressionService, Optional.empty(),
                mock(WorkflowDelegationService.class), null, null, taskEvaluator, taskSpecifications, checkResultDao, null))
                .isInstanceOf(NullPointerException.class).hasMessage("automaticApprovals");
        @SuppressWarnings("unchecked") var provider = (org.springframework.beans.factory.ObjectProvider<WorkflowAutomaticApprovalService>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
        new WorkflowTaskActionService(taskDao, instanceDao, nodeDao, routeDao, eventDao, eventFactory,
                approvalTaskPolicyService, actionPolicyService, progressionService, Optional.empty(),
                mock(WorkflowDelegationService.class), null, null, taskEvaluator, taskSpecifications, checkResultDao, provider);
        verifyNoInteractions(provider);
    }

    @Test
    void shouldApproveApprovalTaskAndCompleteAnyNode() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask sibling = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task, sibling));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(taskDao.updateByIdAndVersion(sibling, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        WorkflowTaskActionResult result = service.approve(request(
                "task-1", "user-1", null, null, "agree", Instant.parse("2026-06-05T02:00:00Z")));

        verify(automaticApprovals).continueFor("instance-1", "user-1", Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
        assertThat(result.task().getDecision()).isEqualTo("approve");
        assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(result.node().getApprovedTaskCount()).isEqualTo(1);
        assertThat(sibling.getTaskStatus()).isEqualTo(WorkflowTaskStatus.SKIPPED);
        assertThat(sibling.getDecision()).isEqualTo("skip");
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_COMPLETED);
        assertThat(result.event().getActionCode()).isEqualTo("approve");
        verify(nodeDao).updateByIdAndVersion(node, 2);
        verify(eventDao, atLeastOnce()).insert(any());
        verify(progressionService).advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T02:00:00Z"), null, null, List.of());
    }

    @Test
    void shouldPassSelectedRouteKeyWhenApprovingTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        service.approve(request("task-1", "user-1", null, null, null, null,
                "agree", Instant.parse("2026-06-05T02:00:00Z"), "manual-left", "choose left"));

        verify(progressionService).advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T02:00:00Z"), "manual-left", "choose left", List.of());
    }

    @Test
    void shouldRequireRuntimeAuthorizationBeforeTaskAction() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        WorkflowActionPolicyService policyService = mock(WorkflowActionPolicyService.class);
        WorkflowTaskActionService authorizedService = new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                policyService,
                progressionService,
                Optional.empty(),
                mock(WorkflowDelegationService.class),
                null,
                null,
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(mock(WorkflowAutomaticApprovalService.class)));
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        authorizedService.approve(WorkflowTaskActionRequest.complete("task-1", "user-1", "agree"));

        verify(policyService).requireRuntimeAction(instance, "approve");
        verify(policyService).requireTaskOperator(task, "approve", "user-1");
    }

    @Test
    void shouldDispatchApprovePluginsAndExposeContext() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        pluginService.approve(WorkflowTaskActionRequest.complete("task-1", "user-1", "agree"));

        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_APPROVE,
                WorkflowRuntimePluginEventType.AFTER_APPROVE);
        WorkflowRuntimePluginContext before = plugin.contexts().getFirst();
        assertThat(before.actionCode()).isEqualTo("approve");
        assertThat(before.moduleAlias()).isEqualTo("sales.contract");
        assertThat(before.recordId()).isEqualTo("record-1");
        assertThat(before.instanceId()).isEqualTo("instance-1");
        assertThat(before.nodeKey()).isEqualTo("approve");
        assertThat(before.taskId()).isEqualTo("task-1");
        assertThat(before.operatorId()).isEqualTo("user-1");
        assertThat(before.reason()).isEqualTo("agree");
    }

    @Test
    void shouldBlockApproveWhenBeforePluginFails() {
        WorkflowRuntimePlugin blocker = new WorkflowRuntimePlugin() {
            @Override
            public String pluginKey() {
                return "blocker";
            }

            @Override
            public void handle(WorkflowRuntimePluginContext context) {
                if (context.eventType() == WorkflowRuntimePluginEventType.BEFORE_APPROVE) {
                    throw new PlatformException("blocked by plugin");
                }
            }
        };
        WorkflowTaskActionService pluginService = serviceWithPlugin(blocker);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> pluginService.approve(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", "agree")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocked by plugin");

        verify(taskDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldForceApproveByAdminWithoutAssigneeCheck() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        WorkflowActionPolicyService policyService = mock(WorkflowActionPolicyService.class);
        WorkflowTaskActionService adminService = new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                policyService,
                progressionService,
                Optional.empty(),
                mock(WorkflowDelegationService.class),
                null,
                null,
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(mock(WorkflowAutomaticApprovalService.class)));
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        WorkflowTaskActionResult result = adminService.forceApprove(request(
                "task-1", "admin-1", null, null, "admin approved", Instant.parse("2026-06-05T02:30:00Z")));

        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
        assertThat(result.task().getActualProcessorId()).isEqualTo("admin-1");
        assertThat(result.task().getDecision()).isEqualTo("forceApprove");
        assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(result.event().getActionCode()).isEqualTo("forceApprove");
        verify(policyService).requireManagementTaskAction("forceApprove", "admin approved");
        verify(policyService, never()).requireRuntimeAction(result.instance(), "forceApprove");
        verify(progressionService).advanceFromNode("instance-1", "approve", "admin-1",
                Instant.parse("2026-06-05T02:30:00Z"), null, null, List.of());
    }

    @Test
    void shouldDispatchForceApprovePluginsAndExposeContext() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        pluginService.forceApprove(request(
                "task-1", "admin-1", null, null, "admin approved",
                Instant.parse("2026-06-05T02:30:00Z")));

        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_APPROVE,
                WorkflowRuntimePluginEventType.AFTER_APPROVE);
        WorkflowRuntimePluginContext before = plugin.contexts().getFirst();
        assertThat(before.actionCode()).isEqualTo("forceApprove");
        assertThat(before.moduleAlias()).isEqualTo("sales.contract");
        assertThat(before.recordId()).isEqualTo("record-1");
        assertThat(before.instanceId()).isEqualTo("instance-1");
        assertThat(before.nodeKey()).isEqualTo("approve");
        assertThat(before.taskId()).isEqualTo("task-1");
        assertThat(before.operatorId()).isEqualTo("admin-1");
        assertThat(before.reason()).isEqualTo("admin approved");
        assertThat(before.instance()).isSameAs(plugin.contexts().getLast().instance());
        assertThat(before.node()).isSameAs(plugin.contexts().getLast().node());
        assertThat(before.task()).isSameAs(plugin.contexts().getLast().task());
    }

    @Test
    void shouldBlockForceApproveWhenBeforePluginFails() {
        WorkflowRuntimePlugin blocker = new WorkflowRuntimePlugin() {
            @Override
            public String pluginKey() {
                return "blocker";
            }

            @Override
            public void handle(WorkflowRuntimePluginContext context) {
                if (context.eventType() == WorkflowRuntimePluginEventType.BEFORE_APPROVE) {
                    throw new PlatformException("blocked by plugin");
                }
            }
        };
        WorkflowTaskActionService pluginService = serviceWithPlugin(blocker);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> pluginService.forceApprove(request(
                "task-1", "admin-1", null, null, "admin approved",
                Instant.parse("2026-06-05T02:30:00Z"))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocked by plugin");

        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        verify(taskDao, never()).updateByIdAndVersion(any(), any());
        verify(nodeDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(eventDao, progressionService);
    }

    @Test
    void shouldRejectForceApproveForBusinessTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        when(taskDao.findById("task-1")).thenReturn(task);

        assertThatThrownBy(() -> service.forceApprove(request(
                "task-1", "admin-1", null, null, "admin approved", Instant.parse("2026-06-05T02:30:00Z"))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only supports approval task");

        verifyNoInteractions(instanceDao, nodeDao, eventDao);
    }

    @Test
    void shouldRejectApprovalTaskAndCreateRestartResubmitTask() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask sibling = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        WorkflowInstance instance = instance();
        instance.setApprovalEnabled(true);
        instance.setStartedBy("starter-1");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task, sibling));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(taskDao.updateByIdAndVersion(sibling, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);

        WorkflowTaskActionResult result = pluginService.reject(request(
                "task-1", "user-1", null, null, "not ok", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.REJECTED);
        assertThat(sibling.getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
        assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.REJECTED);
        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.REJECTED);
        assertThat(result.instance().getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.REJECTED);
        assertThat(result.instance().getRejectResubmitMode()).isEqualTo(WorkflowRejectResubmitMode.RESTART);
        assertThat(result.instance().getRejectReturnNodeKey()).isNull();
        assertThat(result.instance().getLastActionCode()).isEqualTo("reject");
        assertThat(result.createdTask()).isNotNull();
        assertThat(result.createdTask().getTaskKind()).isEqualTo(WorkflowTaskKind.RESUBMIT);
        assertThat(result.createdTask().getAssigneeId()).isEqualTo("starter-1");
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_REJECTED);
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_REJECT,
                WorkflowRuntimePluginEventType.AFTER_REJECT);
        assertThat(plugin.contexts().getFirst().reason()).isEqualTo("not ok");
        verify(instanceDao).updateByIdAndVersion(instance, 5);
        verify(taskDao).insert(result.createdTask());
    }

    @Test
    void shouldRejectApprovalTaskWithReturnToMeMetadata() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssigneeId("manager-1");
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowRejectReturnToMe(true);
        WorkflowInstance instance = instance();
        instance.setApprovalEnabled(true);
        instance.setStartedBy("starter-1");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);

        WorkflowTaskActionResult result = service.reject(request(
                "task-1", "manager-1", null, WorkflowRejectResubmitMode.RETURN_TO_ME,
                "not ok", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(result.instance().getRejectResubmitMode()).isEqualTo(WorkflowRejectResubmitMode.RETURN_TO_ME);
        assertThat(result.instance().getRejectReturnNodeKey()).isEqualTo("approve");
        assertThat(result.instance().getRejectReturnOwnerId()).isEqualTo("manager-1");
        assertThat(result.createdTask().getDueAt()).isNull();
        assertThat(result.createdTask().getTaskKind()).isEqualTo(WorkflowTaskKind.RESUBMIT);
    }

    @Test
    void shouldRejectReturnToMeWhenNodeDoesNotAllowIt() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowRejectReturnToMe(false);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.reject(WorkflowTaskActionRequest.reject(
                "task-1", "user-1", WorkflowRejectResubmitMode.RETURN_TO_ME, "not ok")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not allow reject return to me");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectApprovalRejectWhenNodeDoesNotAllowIt() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowReject(false);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.reject(WorkflowTaskActionRequest.reject(
                "task-1", "user-1", WorkflowRejectResubmitMode.RESTART, "not ok")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not allow reject");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldResubmitReturnToRejectOwnerInSameInstance() {
        WorkflowTask task = task("resubmit-1", WorkflowTaskKind.RESUBMIT, WorkflowTaskStatus.TODO);
        task.setAssigneeId("starter-1");
        task.setNodeInstanceId(null);
        WorkflowInstance instance = instance();
        instance.setInstanceStatus(WorkflowInstanceStatus.REJECTED);
        instance.setApprovalEnabled(true);
        instance.setRejectResubmitMode(WorkflowRejectResubmitMode.RETURN_TO_ME);
        instance.setRejectReturnNodeKey("approve");
        instance.setRejectReturnOwnerId("manager-1");
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setNodeStatus(WorkflowNodeStatus.REJECTED);
        when(taskDao.findById("resubmit-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(node));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);

        WorkflowTaskActionResult result = service.resubmit(request(
                "resubmit-1", "starter-1", null, null, "fixed", Instant.parse("2026-06-05T03:00:00Z")));

        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(instance.getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.PROCESSING);
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("approve");
        assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(result.createdTask()).isNotNull();
        assertThat(result.createdTask().getTaskKind()).isEqualTo(WorkflowTaskKind.APPROVAL);
        assertThat(result.createdTask().getAssigneeId()).isEqualTo("manager-1");
        assertThat(result.createdTask().getDueAt()).isNull();
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_RESUBMITTED);
        verify(taskDao).insert(result.createdTask());
    }

    @Test
    void shouldRejectRestartResubmitInTaskActionEntry() {
        WorkflowTask task = task("resubmit-1", WorkflowTaskKind.RESUBMIT, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        instance.setInstanceStatus(WorkflowInstanceStatus.REJECTED);
        instance.setRejectResubmitMode(WorkflowRejectResubmitMode.RESTART);
        when(taskDao.findById("resubmit-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);

        assertThatThrownBy(() -> service.resubmit(request(
                "resubmit-1", "starter-1", null, null, "fixed", Instant.parse("2026-06-05T03:00:00Z"))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("restart resubmit requires new workflow submit entry");

        verifyNoInteractions(nodeDao, eventDao);
    }

    @Test
    void shouldRollbackToPreviousLinearApprovalNode() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask previousDoneTask = task("task-prev", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        previousDoneTask.setNodeInstanceId("node-prev");
        previousDoneTask.setActualProcessorId("leader-1");
        WorkflowInstance instance = instance();
        instance.setApprovalEnabled(true);
        WorkflowNodeInstance currentNode = node(WorkflowApprovalMode.ALL, null);
        WorkflowNodeInstance previousNode = node("node-prev", "leader", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowRouteInstance route = route("route-1", "leader", "approve", WorkflowRouteStatus.EFFECTIVE);
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(currentNode);
        when(taskDao.query(any(), any())).thenReturn(List.of(currentTask), List.of(currentTask),
                List.of(previousDoneTask));
        when(routeDao.query(any(), any())).thenReturn(List.of(route));
        when(nodeDao.query(any(), any())).thenReturn(List.of(previousNode));
        when(taskDao.updateByIdAndVersion(currentTask, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(currentNode, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(previousNode, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(route, 4)).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);

        WorkflowTaskActionResult result = pluginService.rollback(request(
                "task-1", "user-1", null, null, "return", Instant.parse("2026-06-05T03:00:00Z")));

        assertThat(currentTask.getTaskStatus()).isEqualTo(WorkflowTaskStatus.ROLLED_BACK);
        assertThat(currentNode.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ROLLED_BACK);
        assertThat(currentNode.getRollbackTargetNodeKey()).isEqualTo("leader");
        assertThat(previousNode.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANDIDATE);
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("leader");
        assertThat(result.createdTask()).isNotNull();
        assertThat(result.createdTask().getAssigneeId()).isEqualTo("leader-1");
        assertThat(result.createdTask().getDueAt()).isNull();
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.NODE_ROLLED_BACK);
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_ROLLBACK,
                WorkflowRuntimePluginEventType.AFTER_ROLLBACK);
        assertThat(plugin.contexts().getFirst().rollbackTargetNodeKey()).isEqualTo("leader");
        assertThat(plugin.contexts().getFirst().operatorId()).isEqualTo("user-1");
    }

    @Test
    void shouldRollbackBranchDomainToPreBranchApprovalNode() {
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        currentTask.setNodeInstanceId("node-approve2");
        WorkflowTask branchSiblingTask = task("task-approve3", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        branchSiblingTask.setNodeInstanceId("node-approve3");
        WorkflowTask previousDoneTask = task("task-prev", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        previousDoneTask.setNodeInstanceId("node-approve1");
        previousDoneTask.setActualProcessorId("leader-1");
        WorkflowInstance instance = instance();
        instance.setApprovalEnabled(true);
        WorkflowNodeInstance approve1 = node("node-approve1", "approve1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance branch = node("node-branch", "branch", WorkflowNodeType.BRANCH,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance approve2 = node("node-approve2", "approve2", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.ACTIVE);
        approve2.setActivatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        approve2.setRollbackTargetNodeKey("old");
        WorkflowNodeInstance approve3 = node("node-approve3", "approve3", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.ACTIVE);
        WorkflowNodeInstance converge = node("node-converge", "converge", WorkflowNodeType.CONVERGE,
                WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance approve1ToBranch = route("route-to-branch", "approve1", "branch",
                WorkflowRouteStatus.EFFECTIVE);
        dirtyRoute(approve1ToBranch);
        WorkflowRouteInstance branchToApprove2 = scopedRoute("route-branch-approve2", "branch", "approve2",
                WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance branchToApprove3 = scopedRoute("route-branch-approve3", "branch", "approve3",
                WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance approve2ToConverge = scopedRoute("route-approve2-converge", "approve2", "converge",
                WorkflowRouteStatus.CANDIDATE);
        WorkflowRouteInstance approve3ToConverge = scopedRoute("route-approve3-converge", "approve3", "converge",
                WorkflowRouteStatus.CANDIDATE);
        List<WorkflowRouteInstance> allRoutes = List.of(approve1ToBranch, branchToApprove2, branchToApprove3,
                approve2ToConverge, approve3ToConverge);
        List<WorkflowNodeInstance> allNodes = List.of(approve1, branch, approve2, approve3, converge);
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-approve2")).thenReturn(approve2);
        when(nodeDao.findById("node-approve3")).thenReturn(approve3);
        when(routeDao.query(any(), any())).thenReturn(List.of(branchToApprove2), List.of(approve1ToBranch),
                allRoutes);
        when(nodeDao.query(any(), any())).thenReturn(List.of(branch), List.of(approve1), allNodes);
        when(taskDao.query(any(), any())).thenReturn(List.of(currentTask, branchSiblingTask),
                List.of(branchSiblingTask), List.of(previousDoneTask));
        when(taskDao.updateByIdAndVersion(any(), any())).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(any(), any())).thenReturn(1);
        when(routeDao.updateByIdAndVersion(any(), any())).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);

        WorkflowTaskActionResult result = service.rollback(request(
                "task-1", "user-1", null, null, "return to pre-branch",
                Instant.parse("2026-06-05T03:00:00Z")));

        assertThat(currentTask.getTaskStatus()).isEqualTo(WorkflowTaskStatus.ROLLED_BACK);
        assertThat(branchSiblingTask.getTaskStatus()).isEqualTo(WorkflowTaskStatus.ROLLED_BACK);
        assertThat(approve1.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(result.createdTask()).isNotNull();
        assertThat(result.createdTask().getNodeInstanceId()).isEqualTo("node-approve1");
        assertThat(result.createdTask().getAssigneeId()).isEqualTo("leader-1");
        assertThat(List.of(branch, approve2, approve3, converge))
                .extracting(WorkflowNodeInstance::getNodeStatus)
                .containsOnly(WorkflowNodeStatus.WAITING);
        assertThat(approve2.getActivatedAt()).isNull();
        assertThat(approve2.getRollbackTargetNodeKey()).isNull();
        assertThat(allRoutes).extracting(WorkflowRouteInstance::getRouteStatus)
                .containsOnly(WorkflowRouteStatus.CANDIDATE);
        assertThat(branchToApprove2.getSelectedBy()).isNull();
        assertThat(branchToApprove2.getSelectedAt()).isNull();
        assertThat(branchToApprove2.getSelectedReason()).isNull();
        assertThat(branchToApprove2.getArrivedAt()).isNull();
        assertThat(branchToApprove2.getClosedByRouteId()).isNull();
        assertThat(branchToApprove2.getClosedReason()).isNull();
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("approve1");
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.NODE_ROLLED_BACK);
        verify(taskDao).insert(result.createdTask());
        verify(routeDao, times(5)).updateByIdAndVersion(any(), any());
    }

    @Test
    void shouldRejectRollbackWhenPreviousApprovalNodeIsNotFound() {
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance currentNode = node(WorkflowApprovalMode.ALL, null);
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(currentNode);
        when(routeDao.query(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.rollback(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", "return")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("previous approval node not found");
    }

    @Test
    void shouldRejectRollbackWhenPreviousApprovalNodeIsNotUnique() {
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance currentNode = node(WorkflowApprovalMode.ALL, null);
        WorkflowRouteInstance first = route("route-1", "leader", "approve", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance second = route("route-2", "finance", "approve", WorkflowRouteStatus.EFFECTIVE);
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(currentNode);
        when(routeDao.query(any(), any())).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.rollback(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", "return")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("linear effective route");
    }

    @Test
    void shouldRejectRollbackWhenAnotherNodeIsStillActive() {
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask parallelTask = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        parallelTask.setNodeInstanceId("node-parallel");
        WorkflowInstance instance = instance();
        WorkflowNodeInstance currentNode = node(WorkflowApprovalMode.ALL, null);
        WorkflowNodeInstance previousNode = node("node-prev", "leader", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowRouteInstance route = route("route-1", "leader", "approve", WorkflowRouteStatus.EFFECTIVE);
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(currentNode);
        when(routeDao.query(any(), any())).thenReturn(List.of(route));
        when(nodeDao.query(any(), any())).thenReturn(List.of(previousNode));
        when(taskDao.query(any(), any())).thenReturn(List.of(currentTask, parallelTask));

        assertThatThrownBy(() -> service.rollback(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", "return")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("single active node");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectRollbackForNestedBranchRoute() {
        WorkflowTask currentTask = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        currentTask.setNodeInstanceId("node-approve2");
        WorkflowInstance instance = instance();
        WorkflowNodeInstance currentNode = node("node-approve2", "approve2", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.ACTIVE);
        WorkflowRouteInstance route = scopedRoute("route-nested", "branch", "approve2",
                WorkflowRouteStatus.EFFECTIVE);
        route.setParentRouteId("parent-route");
        when(taskDao.findById("task-1")).thenReturn(currentTask);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-approve2")).thenReturn(currentNode);
        when(routeDao.query(any(), any())).thenReturn(List.of(route));

        assertThatThrownBy(() -> service.rollback(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", "return")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("nested branch route");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldCompleteBusinessTaskAndWriteEvent() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(null, null);
        node.setNodeType(WorkflowNodeType.TASK);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        var completedInstance = instance(); completedInstance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
        when(progressionService.advanceFromNode(anyString(), anyString(), anyString(), any(), any(), any(), anyList()))
                .thenReturn(WorkflowProgressionResult.empty(completedInstance));
        WorkflowTaskActionResult result = service.completeBusinessTask(request(
                "task-1", "user-1", null, null, "done", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(result.instance()).isSameAs(completedInstance);
        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
        assertThat(result.task().getActualProcessorId()).isEqualTo("user-1");
        assertThat(result.task().getDecision()).isEqualTo("complete");
        assertThat(result.task().getCompletedAt()).isEqualTo(Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(result.task().getVersion()).isEqualTo(4);
        assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_COMPLETED);
        assertThat(result.event().getActionCode()).isEqualTo("complete");
        verify(taskDao).updateByIdAndVersion(task, 3);
        verify(nodeDao).updateByIdAndVersion(node, 2);
        verify(eventDao).insert(result.event());
        verify(progressionService).advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T02:00:00Z"), null, null, List.of());
    }

    @Test
    void shouldRecheckBusinessFactsEvenWhenHistoricalTaskCheckPassed() {
        var task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        task.setCheckStatus(WorkflowTaskCheckStatus.PASSED);
        var instance = instance();
        var node = node(null, null); node.setNodeType(WorkflowNodeType.TASK);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(WorkflowModuleTaskEvaluation.failed("current business facts no longer pass", List.of(), List.of()));

        assertThatThrownBy(() -> service.completeBusinessTask(WorkflowTaskActionRequest.complete("task-1", "user-1", "done")))
                .isInstanceOf(PlatformException.class).hasMessageContaining("no longer pass");
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        verify(taskDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(eventDao, checkResultDao, progressionService);
    }

    @Test
    void shouldPassSelectedRouteKeyWhenCompletingBusinessTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(null, null);
        node.setNodeType(WorkflowNodeType.TASK);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        service.completeBusinessTask(request("task-1", "user-1", null, null, null, null,
                "done", Instant.parse("2026-06-05T02:00:00Z"), "manual-right", "choose right"));

        verify(progressionService).advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T02:00:00Z"), "manual-right", "choose right", List.of());
    }

    @Test
    void shouldCreateDelegationCompletionNoticeWhenDelegateApprovesTask() {
        WorkflowTask task = delegatedTask("task-1", WorkflowTaskKind.APPROVAL);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        WorkflowTaskActionService noticeService = serviceWithDelegationCompletionNotice();
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        noticeService.approve(request(
                "task-1", "delegate-1", null, null, "agree", Instant.parse("2026-06-05T02:00:00Z")));

        ArgumentCaptor<WorkflowTask> taskCaptor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(taskDao).insert(taskCaptor.capture());
        WorkflowTask notice = taskCaptor.getValue();
        assertThat(notice.getTaskKind()).isEqualTo(WorkflowTaskKind.NOTICE);
        assertThat(notice.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(notice.getAssigneeId()).isEqualTo("principal-1");
        assertThat(notice.getParentTaskId()).isEqualTo("task-1");
        assertThat(notice.getOriginalAssigneeId()).isEqualTo("principal-1");
        assertThat(notice.getDelegatedFromUserId()).isEqualTo("principal-1");
        assertThat(notice.getDelegatedToUserId()).isEqualTo("delegate-1");
        assertThat(notice.getDelegationPolicyId()).isEqualTo("delegation-1");
        assertThat(notice.getActualProcessorId()).isEqualTo("delegate-1");
        assertThat(notice.getCompletedAt()).isEqualTo(Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(notice.getAssignmentSnapshotText()).contains("DELEGATION_COMPLETED", "\"sourceTaskId\":\"task-1\"");

        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao, times(2)).insert(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(WorkflowEvent::getEventType)
                .contains(WorkflowEventType.TASK_COMPLETED, WorkflowEventType.DELEGATION_COMPLETED);
        WorkflowEvent delegationEvent = eventCaptor.getAllValues().stream()
                .filter(event -> event.getEventType() == WorkflowEventType.DELEGATION_COMPLETED)
                .findFirst()
                .orElseThrow();
        assertThat(delegationEvent.getTaskId()).isEqualTo("task-1");
        assertThat(delegationEvent.getPayloadText()).contains("\"sourceTaskId\":\"task-1\"");
        assertThat(delegationEvent.getPayloadText()).contains("\"noticeTaskId\":\"" + notice.getId() + "\"");
        assertThat(delegationEvent.getPayloadText()).contains("\"actualProcessorId\":\"delegate-1\"");
    }

    @Test
    void shouldNotCreateDelegationCompletionNoticeWhenPrincipalProcessesOwnDelegatedTask() {
        WorkflowTask task = delegatedTask("task-1", WorkflowTaskKind.APPROVAL);
        task.setPrincipalCanProcess(true);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ANY, null);
        WorkflowTaskActionService noticeService = serviceWithDelegationCompletionNotice();
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        noticeService.approve(request(
                "task-1", "principal-1", null, null, "agree", Instant.parse("2026-06-05T02:00:00Z")));

        verify(taskDao, never()).insert(any(WorkflowTask.class));
        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(WorkflowEventType.TASK_COMPLETED);
    }

    @Test
    void shouldCreateDelegationCompletionNoticeWhenTransferredDelegationTaskIsCompleted() {
        WorkflowTask task = delegatedTask("task-1", WorkflowTaskKind.BUSINESS);
        task.setAssignmentKind(WorkflowAssignmentKind.TRANSFERRED);
        task.setAssigneeId("user-b");
        task.setTransferredFromUserId("delegate-1");
        task.setTransferredBy("delegate-1");
        task.setTransferredAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowNodeInstance node = node(null, null);
        node.setNodeType(WorkflowNodeType.TASK);
        WorkflowTaskActionService noticeService = serviceWithDelegationCompletionNotice();
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of());
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);

        noticeService.completeBusinessTask(request(
                "task-1", "user-b", null, null, "done", Instant.parse("2026-06-05T03:00:00Z")));

        ArgumentCaptor<WorkflowTask> taskCaptor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(taskDao).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getAssigneeId()).isEqualTo("principal-1");
        assertThat(taskCaptor.getValue().getAssignmentKind()).isEqualTo(WorkflowAssignmentKind.TRANSFERRED);
        assertThat(taskCaptor.getValue().getTransferredFromUserId()).isEqualTo("delegate-1");
        assertThat(taskCaptor.getValue().getActualProcessorId()).isEqualTo("user-b");
        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao, times(2)).insert(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(WorkflowEvent::getEventType)
                .contains(WorkflowEventType.TASK_COMPLETED, WorkflowEventType.DELEGATION_COMPLETED);
    }

    @Test
    void shouldMarkNoticeTaskAsNoticed() {
        WorkflowTask task = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        when(taskDao.findById("notice-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);

        WorkflowTaskActionResult result = service.notice(request(
                "notice-1", "user-1", null, null, "read", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.NOTICED);
        assertThat(result.task().getDecision()).isEqualTo("notice");
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_COMPLETED);
        assertThat(result.event().getActionCode()).isEqualTo("notice");
    }

    @Test
    void shouldReadNoticeTaskWithOwnerCheckAndIdempotentStatus() {
        WorkflowTask unread = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        when(taskDao.findById("notice-1")).thenReturn(unread);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(taskDao.updateByIdAndVersion(unread, 3)).thenReturn(1);

        WorkflowTaskActionResult unreadResult = service.readNotice(request(
                "notice-1", "user-1", null, null, "read", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(unreadResult.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.NOTICED);
        assertThat(unreadResult.event()).isNotNull();

        WorkflowTask read = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.NOTICED);
        when(taskDao.findById("notice-2")).thenReturn(read);
        when(instanceDao.findById("instance-1")).thenReturn(instance());

        WorkflowTaskActionResult readResult = service.readNotice(request(
                "notice-2", "user-1", null, null, "read again", Instant.parse("2026-06-05T03:00:00Z")));

        assertThat(readResult.task()).isSameAs(read);
        assertThat(readResult.event()).isNull();
        verify(taskDao, never()).updateByIdAndVersion(read, 3);
    }

    @Test
    void shouldRejectNoticeReadForOtherOwnerOrInvalidTaskState() {
        WorkflowTask otherOwner = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        otherOwner.setAssigneeId("user-2");
        when(taskDao.findById("notice-1")).thenReturn(otherOwner);
        when(instanceDao.findById("instance-1")).thenReturn(instance());

        assertThatThrownBy(() -> service.readNotice(WorkflowTaskActionRequest.complete(
                "notice-1", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reader is not assignee");

        WorkflowTask doneApproval = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(taskDao.findById("task-1")).thenReturn(doneApproval);
        assertThatThrownBy(() -> service.readNotice(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not a notice task");

        WorkflowTask canceledNotice = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.CANCELED);
        when(taskDao.findById("notice-2")).thenReturn(canceledNotice);
        assertThatThrownBy(() -> service.readNotice(WorkflowTaskActionRequest.complete(
                "notice-2", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not readable");
    }

    @Test
    void shouldTransferTodoTaskByCreatingNewAssigneeTask() {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        task.setAssigneeId("user-a");
        task.setOriginalAssigneeId("user-a");
        task.setOwnerId("owner-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("user-a");
        task.setPrincipalCanProcess(true);
        task.setDelegationPolicyId("delegation-1");
        task.setDueAt(Instant.parse("2026-06-06T02:00:00Z"));
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(1);

        WorkflowTaskActionResult result = pluginService.transfer(request(
                "task-1", "user-a", "user-b", null, "handover", Instant.parse("2026-06-05T02:00:00Z")));

        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.TRANSFERRED);
        assertThat(result.task().getTransferredBy()).isEqualTo("user-a");
        assertThat(result.task().getActualProcessorId()).isEqualTo("user-a");
        assertThat(result.task().getDecision()).isEqualTo("transfer");
        assertThat(result.createdTask()).isNotNull();
        assertThat(result.createdTask().getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(result.createdTask().getAssignmentKind()).isEqualTo(WorkflowAssignmentKind.TRANSFERRED);
        assertThat(result.createdTask().getOriginTaskId()).isEqualTo("task-1");
        assertThat(result.createdTask().getAssigneeId()).isEqualTo("user-b");
        assertThat(result.createdTask().getTransferredFromUserId()).isEqualTo("user-a");
        assertThat(result.createdTask().getOriginalAssigneeId()).isEqualTo("user-a");
        assertThat(result.createdTask().getDelegatedFromUserId()).isEqualTo("principal-1");
        assertThat(result.createdTask().getDelegatedToUserId()).isEqualTo("user-a");
        assertThat(result.createdTask().getPrincipalCanProcess()).isTrue();
        assertThat(result.createdTask().getDelegationPolicyId()).isEqualTo("delegation-1");
        assertThat(result.createdTask().getDueAt()).isEqualTo(Instant.parse("2026-06-06T02:00:00Z"));
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.TASK_TRANSFERRED);
        assertThat(plugin.events()).containsExactly(WorkflowRuntimePluginEventType.BEFORE_TRANSFER,
                WorkflowRuntimePluginEventType.AFTER_TRANSFER);
        assertThat(plugin.contexts().getFirst().targetAssigneeId()).isEqualTo("user-b");
        assertThat(plugin.contexts().getFirst().nodeKey()).isEqualTo("approve");
        assertThat(plugin.contexts().getFirst().operatorId()).isEqualTo("user-a");
        assertThat(plugin.contexts().getFirst().reason()).isEqualTo("handover");
        verify(taskDao).insert(result.createdTask());
        verify(eventDao).insert(result.event());
        assertThat(result.event().getEventType()).isNotEqualTo(WorkflowEventType.DELEGATION_COMPLETED);
    }

    @Test
    void shouldInsertRuntimeAddSignSegmentWithoutCompletingCurrentTask() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowTaskActionService pluginService = serviceWithPlugin(plugin);
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowRouteInstance originalRoute = route("route-1", "approve", "next", WorkflowRouteStatus.CANDIDATE);
        originalRoute.setRouteKey("approve-next");
        originalRoute.setDefaultRoute(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(originalRoute));
        when(nodeDao.query(any(), any())).thenReturn(List.of(), List.of(node, node("node-next", "next",
                WorkflowNodeType.END, WorkflowNodeStatus.WAITING)));
        when(routeDao.updateByIdAndVersion(originalRoute, 4)).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(any(WorkflowInstance.class), any(Integer.class))).thenReturn(1);

        WorkflowTaskActionResult result = pluginService.addSign(request(
                "task-1", "user-1", null, null, segment("add-1", "approve", "next"), null,
                "need finance review", Instant.parse("2026-06-05T04:00:00Z"),
                null, null, List.of(), "{\"nodes\":[\"add-1\"]}", "{\"zoom\":1}"));

        assertThat(result.task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(result.createdTask()).isNull();
        assertThat(result.addSignEditMode()).isEqualTo(WorkflowAddSignEditMode.CREATE);
        assertThat(result.sourceNodeKey()).isEqualTo("approve");
        assertThat(result.addedNodeKeys()).containsExactly("add-1");
        assertThat(result.replacedRouteIds()).containsExactly("route-1");
        assertThat(originalRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(originalRoute.getClosedReason()).isEqualTo("workflow route replaced by addSign");
        ArgumentCaptor<WorkflowNodeInstance> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeInstance.class);
        verify(nodeDao).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getNodeKey()).isEqualTo("add-1");
        assertThat(nodeCaptor.getValue().getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(nodeCaptor.getValue().getParticipantPolicyText()).isEqualTo("user:add-signer-1");
        assertThat(nodeCaptor.getValue().getAddedByAddSign()).isTrue();
        assertThat(nodeCaptor.getValue().getAddSignSourceNodeKey()).isEqualTo("approve");
        assertThat(nodeCaptor.getValue().getAddSignOperatorId()).isEqualTo("user-1");
        assertThat(nodeCaptor.getValue().getAddSignAt()).isEqualTo(Instant.parse("2026-06-05T04:00:00Z"));
        ArgumentCaptor<WorkflowRouteInstance> routeCaptor = ArgumentCaptor.forClass(WorkflowRouteInstance.class);
        verify(routeDao, times(2)).insert(routeCaptor.capture());
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getSourceNodeKey)
                .containsExactly("approve", "add-1");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getTargetNodeKey)
                .containsExactly("add-1", "next");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getRouteStatus)
                .containsOnly(WorkflowRouteStatus.CANDIDATE);
        assertThat(result.event().getEventType()).isEqualTo(WorkflowEventType.ADD_SIGN);
        assertThat(result.event().getActionCode()).isEqualTo("addSign");
        assertThat(result.event().getTaskId()).isEqualTo("task-1");
        assertThat(result.event().getPayloadText()).contains("\"sourceNodeKey\":\"approve\"");
        JsonNode payload = OBJECT_MAPPER.readTree(result.event().getPayloadText());
        assertThat(payload.path("addedNodeKeys").get(0).asText()).isEqualTo("add-1");
        assertThat(payload.path("replacedRouteIds").get(0).asText()).isEqualTo("route-1");
        assertThat(payload.path("editMode").asText()).isEqualTo("create");
        assertThat(payload.path("semanticJson").asText()).isEqualTo("{\"nodes\":[\"add-1\"]}");
        assertThat(payload.path("layoutJson").asText()).isEqualTo("{\"zoom\":1}");
        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceDao).updateByIdAndVersion(instanceCaptor.capture(), any(Integer.class));
        assertThat(instanceCaptor.getValue().getSemanticJson()).isEqualTo("{\"nodes\":[\"add-1\"]}");
        assertThat(instanceCaptor.getValue().getLayoutJson()).isEqualTo("{\"zoom\":1}");
        verify(taskDao, never()).insert(any());
        verify(eventDao).insert(result.event());
        verifyNoInteractions(progressionService);
        assertThat(plugin.contexts()).isEmpty();
    }

    @Test
    void shouldInsertRuntimeAddSignBranchConvergeSegment() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowRouteInstance originalRoute = route("route-1", "approve", "next", WorkflowRouteStatus.CANDIDATE);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(originalRoute));
        when(nodeDao.query(any(), any())).thenReturn(List.of(), List.of(node, node("node-next", "next",
                WorkflowNodeType.END, WorkflowNodeStatus.WAITING)));
        when(routeDao.updateByIdAndVersion(originalRoute, 4)).thenReturn(1);

        WorkflowTaskActionResult result = service.addSign(request(
                "task-1", "user-1", null, null, branchConvergeSegment(), null,
                "need parallel review", Instant.parse("2026-06-05T04:00:00Z")));

        assertThat(result.addSignEditMode()).isEqualTo(WorkflowAddSignEditMode.CREATE);
        assertThat(result.addedNodeKeys()).containsExactly("addBranch", "addA", "addB", "addConverge");
        assertThat(result.createdTask()).isNull();
        ArgumentCaptor<WorkflowNodeInstance> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeInstance.class);
        verify(nodeDao, times(4)).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getAllValues()).extracting(WorkflowNodeInstance::getNodeKey)
                .containsExactly("addBranch", "addA", "addB", "addConverge");
        assertThat(nodeCaptor.getAllValues()).extracting(WorkflowNodeInstance::getNodeType)
                .containsExactly(WorkflowNodeType.BRANCH, WorkflowNodeType.APPROVAL, WorkflowNodeType.APPROVAL,
                        WorkflowNodeType.CONVERGE);
        assertThat(nodeCaptor.getAllValues()).extracting(WorkflowNodeInstance::getAddedByAddSign)
                .containsOnly(true);
        assertThat(nodeCaptor.getAllValues()).extracting(WorkflowNodeInstance::getAddSignSourceNodeKey)
                .containsOnly("approve");
        assertThat(nodeCaptor.getAllValues()).extracting(WorkflowNodeInstance::getAddSignOperatorId)
                .containsOnly("user-1");
        ArgumentCaptor<WorkflowRouteInstance> routeCaptor = ArgumentCaptor.forClass(WorkflowRouteInstance.class);
        verify(routeDao, times(6)).insert(routeCaptor.capture());
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getSourceNodeKey)
                .containsExactly("approve", "addBranch", "addBranch", "addA", "addB", "addConverge");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getTargetNodeKey)
                .containsExactly("addBranch", "addA", "addB", "addConverge", "addConverge", "next");
        assertThat(result.event().getPayloadText())
                .contains("\"addedNodeKeys\":[\"addBranch\",\"addA\",\"addB\",\"addConverge\"]");
        verify(taskDao, never()).insert(any());
        verifyNoInteractions(progressionService);
    }

    @Test
    void shouldAnchorRecursiveAddSignToCurrentAddSignNode() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setNodeInstanceId("node-add-current");
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node("node-add-current", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.ACTIVE);
        node.setApprovalMode(WorkflowApprovalMode.ALL);
        node.setAllowAddSign(true);
        node.setAddedByAddSign(true);
        node.setAddSignSourceNodeKey("approve");
        WorkflowNodeInstance next = node("node-next", "next2", WorkflowNodeType.END, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance originalRoute = route("route-add-next", "add-1", "next2",
                WorkflowRouteStatus.CANDIDATE);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-add-current")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(originalRoute));
        when(nodeDao.query(any(), any())).thenReturn(List.of(), List.of(node, next));
        when(routeDao.updateByIdAndVersion(originalRoute, 4)).thenReturn(1);

        WorkflowTaskActionResult result = service.addSign(request(
                "task-1", "user-1", null, null, segment("add-2", "add-1", "next2"), null,
                "recursive review", Instant.parse("2026-06-05T04:30:00Z")));

        assertThat(result.addSignEditMode()).isEqualTo(WorkflowAddSignEditMode.CREATE);
        assertThat(result.sourceNodeKey()).isEqualTo("add-1");
        assertThat(result.addedNodeKeys()).containsExactly("add-2");
        ArgumentCaptor<WorkflowNodeInstance> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeInstance.class);
        verify(nodeDao).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getAddSignSourceNodeKey()).isEqualTo("add-1");
        assertThat(nodeCaptor.getValue().getAddSignOperatorId()).isEqualTo("user-1");
        ArgumentCaptor<WorkflowRouteInstance> routeCaptor = ArgumentCaptor.forClass(WorkflowRouteInstance.class);
        verify(routeDao, times(2)).insert(routeCaptor.capture());
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getSourceNodeKey)
                .containsExactly("add-1", "add-2");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getTargetNodeKey)
                .containsExactly("add-2", "next2");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getAddSignSourceNodeKey)
                .containsOnly("add-1");
        assertThat(result.event().getPayloadText()).contains("\"sourceNodeKey\":\"add-1\"");
        verifyNoInteractions(progressionService);
    }

    @Test
    void shouldReplaceUnstartedRuntimeAddSignSegmentFromSameSourceNode() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowNodeInstance oldAdd = node("node-old-add", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.WAITING);
        oldAdd.setAddedByAddSign(true);
        oldAdd.setAddSignSourceNodeKey("approve");
        WorkflowNodeInstance next = node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance oldEntry = route("route-old-entry", "approve", "add-1",
                WorkflowRouteStatus.CANDIDATE);
        oldEntry.setAddedByAddSign(true);
        oldEntry.setAddSignSourceNodeKey("approve");
        WorkflowRouteInstance oldExit = route("route-old-exit", "add-1", "next",
                WorkflowRouteStatus.CANDIDATE);
        oldExit.setAddedByAddSign(true);
        oldExit.setAddSignSourceNodeKey("approve");
        oldExit.setDefaultRoute(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of(task));
        when(nodeDao.query(any(), any())).thenReturn(List.of(oldAdd), List.of(node, oldAdd, next));
        when(routeDao.query(any(), any())).thenReturn(List.of(oldEntry, oldExit));

        WorkflowTaskActionResult result = service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "replace review"));

        assertThat(result.addSignEditMode()).isEqualTo(WorkflowAddSignEditMode.REPLACE);
        assertThat(result.addedNodeKeys()).containsExactly("add-1");
        assertThat(result.replacedRouteIds()).containsExactly("route-old-entry", "route-old-exit");
        verify(routeDao).deleteById("route-old-entry");
        verify(routeDao).deleteById("route-old-exit");
        verify(nodeDao).deleteById("node-old-add");
        verify(routeDao, never()).updateByIdAndVersion(any(), any());
        ArgumentCaptor<WorkflowNodeInstance> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeInstance.class);
        verify(nodeDao).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getNodeKey()).isEqualTo("add-1");
        assertThat(nodeCaptor.getValue().getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(nodeCaptor.getValue().getAddedByAddSign()).isTrue();
        ArgumentCaptor<WorkflowRouteInstance> routeCaptor = ArgumentCaptor.forClass(WorkflowRouteInstance.class);
        verify(routeDao, times(2)).insert(routeCaptor.capture());
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getSourceNodeKey)
                .containsExactly("approve", "add-1");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getTargetNodeKey)
                .containsExactly("add-1", "next");
        assertThat(routeCaptor.getAllValues()).extracting(WorkflowRouteInstance::getRouteStatus)
                .containsOnly(WorkflowRouteStatus.CANDIDATE);
        assertThat(result.event().getPayloadText()).contains("\"editMode\":\"replace\"");
        assertThat(result.event().getPayloadText())
                .contains("\"replacedRouteIds\":[\"route-old-entry\",\"route-old-exit\"]");
        verify(eventDao).insert(result.event());
        verifyNoInteractions(progressionService);
    }

    @Test
    void shouldRejectReplacingAddSignSegmentWhenEditableSegmentHasMultipleExitRoutes() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowNodeInstance oldAdd = node("node-old-add", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.WAITING);
        oldAdd.setAddedByAddSign(true);
        oldAdd.setAddSignSourceNodeKey("approve");
        WorkflowRouteInstance oldEntry = route("route-old-entry", "approve", "add-1",
                WorkflowRouteStatus.CANDIDATE);
        oldEntry.setAddedByAddSign(true);
        oldEntry.setAddSignSourceNodeKey("approve");
        WorkflowRouteInstance oldExit = route("route-old-exit", "add-1", "next",
                WorkflowRouteStatus.CANDIDATE);
        oldExit.setAddedByAddSign(true);
        oldExit.setAddSignSourceNodeKey("approve");
        oldExit.setDefaultRoute(true);
        WorkflowRouteInstance oldSecondExit = route("route-old-second-exit", "add-1", "next2",
                WorkflowRouteStatus.CANDIDATE);
        oldSecondExit.setAddedByAddSign(true);
        oldSecondExit.setAddSignSourceNodeKey("approve");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of(task));
        when(nodeDao.query(any(), any())).thenReturn(List.of(oldAdd));
        when(routeDao.query(any(), any())).thenReturn(List.of(oldEntry, oldExit, oldSecondExit));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-2", "approve", "next"), "replace review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("single editable exit route");

        verify(routeDao, never()).deleteById(any());
        verify(nodeDao, never()).deleteById(any());
        verify(nodeDao, never()).insert(any());
        verify(routeDao, never()).insert(any());
        verify(routeDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(eventDao);
        verifyNoInteractions(progressionService);
    }

    @Test
    void shouldRejectReplacingAddSignSegmentWhenExistingNodeIsEffective() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowNodeInstance activeAdd = node("node-old-add", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.ACTIVE);
        activeAdd.setAddedByAddSign(true);
        activeAdd.setAddSignSourceNodeKey("approve");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task));
        when(nodeDao.query(any(), any())).thenReturn(List.of(activeAdd));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "replace review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already effective and cannot be edited");

        verify(routeDao, never()).deleteById(any());
        verify(nodeDao, never()).deleteById(any());
        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectReplacingAddSignSegmentWhenExistingNodeAlreadyHasTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask oldAddTask = task("task-old-add", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        oldAddTask.setNodeInstanceId("node-old-add");
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowNodeInstance oldAdd = node("node-old-add", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.WAITING);
        oldAdd.setAddedByAddSign(true);
        oldAdd.setAddSignSourceNodeKey("approve");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of(task, oldAddTask));
        when(nodeDao.query(any(), any())).thenReturn(List.of(oldAdd));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "replace review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already effective and cannot be edited");

        verify(routeDao, never()).deleteById(any());
        verify(nodeDao, never()).deleteById(any());
        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRequireRuntimeAuthorizationBeforeAddSign() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowActionPolicyService policyService = mock(WorkflowActionPolicyService.class);
        WorkflowTaskActionService authorizedService = new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                policyService,
                progressionService,
                Optional.empty(),
                mock(WorkflowDelegationService.class),
                null,
                null,
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(mock(WorkflowAutomaticApprovalService.class)));
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(route("route-1", "approve", "next",
                WorkflowRouteStatus.CANDIDATE)));
        when(nodeDao.query(any(), any())).thenReturn(List.of(), List.of(node, node("node-next", "next",
                WorkflowNodeType.END, WorkflowNodeStatus.WAITING)));
        when(routeDao.updateByIdAndVersion(any(), any())).thenReturn(1);

        authorizedService.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "need review"));

        verify(policyService).requireRuntimeAction(instance, "addSign");
        verify(policyService).requireNodeTaskAction(task, node, "addSign", "user-1", "need review");
    }

    @Test
    void shouldRejectAddSignWhenNodeDoesNotAllowIt() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(false);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not allow add sign");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectAddSignForNonApprovalTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        when(taskDao.findById("task-1")).thenReturn(task);

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only supports approval task");

        verifyNoInteractions(instanceDao, nodeDao, eventDao);
    }

    @Test
    void shouldRejectAddSignWhenNodeHasMultipleTodoApprovalTasks() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask sibling = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task, sibling));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("add-1", "approve", "next"), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("multi todo approval tasks");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectInvalidAddSignSegments() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowRouteInstance originalRoute = route("route-1", "approve", "next", WorkflowRouteStatus.CANDIDATE);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(originalRoute));
        when(nodeDao.query(any(), any())).thenReturn(
                List.of(),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(List.of(), List.of()), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must contain nodes");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1",
                new WorkflowAddSignSegment(List.of(nodeDefinition("add-1")), List.of(
                        linkDefinition("direct", "approve", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("empty direct route");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1",
                new WorkflowAddSignSegment(List.of(nodeDefinition("add-1")), List.of(
                        linkDefinition("entry", "approve", "add-1"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("one exit");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", segment("approve", "approve", "next"), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("conflicts");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1",
                new WorkflowAddSignSegment(List.of(nodeDefinition("add-missing", null)), List.of(
                        linkDefinition("entry-missing", "approve", "add-missing"),
                        linkDefinition("exit-missing", "add-missing", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("participant policy is required");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1",
                new WorkflowAddSignSegment(List.of(nodeDefinition("add-invalid", "role:finance")), List.of(
                        linkDefinition("entry-invalid", "approve", "add-invalid"),
                        linkDefinition("exit-invalid", "add-invalid", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow participant policy");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1",
                new WorkflowAddSignSegment(List.of(nodeDefinition("add-task", WorkflowNodeType.TASK,
                        "user:add-signer-1")), List.of(
                        linkDefinition("entry-task", "approve", "add-task"),
                        linkDefinition("exit-task", "add-task", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only supports approval, branch and converge nodes");
    }

    @Test
    void shouldRejectInvalidAddSignBranchConvergeSegments() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setAllowAddSign(true);
        WorkflowRouteInstance originalRoute = route("route-1", "approve", "next", WorkflowRouteStatus.CANDIDATE);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(any(), any())).thenReturn(List.of(task), List.of(), List.of(task), List.of(),
                List.of(task), List.of(), List.of(task), List.of(), List.of(task), List.of());
        when(routeDao.query(any(), any())).thenReturn(List.of(originalRoute));
        when(routeDao.updateByIdAndVersion(originalRoute, 4)).thenReturn(1);
        when(nodeDao.query(any(), any())).thenReturn(
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)),
                List.of(),
                List.of(node, node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING)));

        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(
                        List.of(nodeDefinition("addA"), nodeDefinition("addB")),
                        List.of(
                                linkDefinition("entry", "approve", "addA"),
                                linkDefinition("a-exit", "addA", "next"),
                                linkDefinition("b-exit", "addB", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("one exit");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(
                        List.of(nodeDefinition("addA"), nodeDefinition("isolated")),
                        List.of(
                                linkDefinition("entry", "approve", "addA"),
                                linkDefinition("exit", "addA", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unreachable added node");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(
                        List.of(nodeDefinition("addA"), nodeDefinition("dead")),
                        List.of(
                                linkDefinition("entry-a", "approve", "addA"),
                                linkDefinition("entry-dead", "approve", "dead"),
                                linkDefinition("exit", "addA", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot reach original next node");
        assertThatThrownBy(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(
                        List.of(nodeDefinition("addA"), nodeDefinition("addB")),
                        List.of(
                                linkDefinition("entry", "approve", "addA"),
                                linkDefinition("a-b", "addA", "addB"),
                                linkDefinition("b-a", "addB", "addA"),
                                linkDefinition("exit", "addA", "next"))), "need review")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("invalid cycle");
        assertThatCode(() -> service.addSign(WorkflowTaskActionRequest.addSign(
                "task-1", "user-1", new WorkflowAddSignSegment(
                        List.of(nodeDefinition("add-branch", WorkflowNodeType.BRANCH, null),
                                nodeDefinition("add-converge", WorkflowNodeType.CONVERGE, null)),
                        List.of(
                                linkDefinition("entry", "approve", "add-branch"),
                                linkDefinition("middle", "add-branch", "add-converge"),
                                linkDefinition("exit", "add-converge", "next"))), "need review")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTaskActionWhenOperatorIsNotAssignee() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.approve(WorkflowTaskActionRequest.complete(
                "task-1", "other-user", "agree")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("operator is not assignee");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRequireReasonForRiskTaskAction() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(WorkflowApprovalMode.ALL, null);
        node.setRequireRejectReason(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.reject(WorkflowTaskActionRequest.reject(
                "task-1", "user-1", WorkflowRejectResubmitMode.RESTART, null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reason is required");

        verifyNoInteractions(eventDao);
    }

    @Test
    void shouldRejectCompletedTaskAction() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.DONE);
        when(taskDao.findById("task-1")).thenReturn(task);

        assertThatThrownBy(() -> service.completeBusinessTask(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow task is not todo");

        verifyNoInteractions(instanceDao, eventDao);
    }

    @Test
    void shouldRejectBusinessCompleteForApprovalTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(taskDao.findById("task-1")).thenReturn(task);

        assertThatThrownBy(() -> service.completeBusinessTask(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not a business task");

        verifyNoInteractions(instanceDao, eventDao);
    }

    @Test
    void shouldThrowOptimisticLockWhenTaskUpdateLosesRace() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node(null, null);
        node.setNodeType(WorkflowNodeType.TASK);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance());
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.updateByIdAndVersion(task, 3)).thenReturn(0);

        assertThatThrownBy(() -> service.completeBusinessTask(WorkflowTaskActionRequest.complete(
                "task-1", "user-1", null)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("workflow task version conflict");

        verifyNoInteractions(eventDao);
    }

    @Test
    void withdrawingVoteFromActiveParallelNodeRetainsOtherActiveKeysAndRecountsOnlyEffectiveApprovedVotes() {
        var original = task("vote", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        original.setActualProcessorId("user-1"); original.setDecision("approve");
        original.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z")); original.setCreatedBy("original-creator");
        var approved = task("other-vote", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE); approved.setAssigneeId("user-2");
        var historical = task("history", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.ROLLED_BACK);
        var source = node(WorkflowApprovalMode.ALL, null);
        var parallel = node("parallel", "parallelApproval", WorkflowNodeType.APPROVAL, WorkflowNodeStatus.ACTIVE);
        var instance = instance();
        when(taskDao.findById("vote")).thenReturn(original); when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(source);
        when(nodeDao.query(any(), any())).thenReturn(List.of(source, parallel)); when(routeDao.query(any(), any())).thenReturn(List.of());
        when(taskDao.query(any(), any())).thenReturn(List.of(original, approved, historical));
        when(taskDao.updateByIdAndVersion(any(), any())).thenReturn(1); when(nodeDao.updateByIdAndVersion(any(), any())).thenReturn(1);
        when(instanceDao.updateByIdAndVersion(any(), any())).thenReturn(1);
        var now = Instant.parse("2026-10-08T00:00:00Z");
        try (var user = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                net.ximatai.muyun.spring.common.identity.CurrentUser.systemUser("user-1", "actor"))) {
            var result = service.revokeApprove(WorkflowTaskActionRequest.builder("vote", "user-1").reason("correct vote").operatedAt(now).build());
            assertThat(result.instance().getCurrentNodeKeys().split(",")).containsExactlyInAnyOrder("approve", "parallelApproval");
            assertThat(result.node().getApprovedTaskCount()).isOne(); assertThat(result.node().getCompletedTaskCount()).isOne();
            assertThat(approved.getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(result.createdTask().getCreatedAt()).isEqualTo(now); assertThat(result.createdTask().getCreatedBy()).isEqualTo("user-1");
        }
    }

    private WorkflowTask task(String id, WorkflowTaskKind kind, WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setTenantId("tenant-1");
        task.setVersion(3);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskKind(kind);
        task.setTaskStatus(status);
        task.setAssigneeId("user-1");
        task.setCheckStatus(WorkflowTaskCheckStatus.NOT_CHECKED);
        return task;
    }

    private WorkflowTask delegatedTask(String id, WorkflowTaskKind kind) {
        WorkflowTask task = task(id, kind, WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setOwnerId("principal-1");
        task.setAssigneeId("delegate-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("delegate-1");
        task.setPrincipalCanProcess(false);
        task.setDelegationPolicyId("delegation-1");
        task.setAssignmentSnapshotText("{\"delegationPolicyId\":\"delegation-1\"}");
        return task;
    }

    private WorkflowAddSignSegment segment(String addedNodeKey, String sourceNodeKey, String nextNodeKey) {
        return new WorkflowAddSignSegment(List.of(nodeDefinition(addedNodeKey)), List.of(
                linkDefinition("entry-" + addedNodeKey, sourceNodeKey, addedNodeKey),
                linkDefinition("exit-" + addedNodeKey, addedNodeKey, nextNodeKey)
        ));
    }

    private WorkflowAddSignSegment branchConvergeSegment() {
        return new WorkflowAddSignSegment(List.of(
                nodeDefinition("addBranch", WorkflowNodeType.BRANCH, null),
                nodeDefinition("addA"),
                nodeDefinition("addB"),
                nodeDefinition("addConverge", WorkflowNodeType.CONVERGE, null)
        ), List.of(
                linkDefinition("entry-branch", "approve", "addBranch"),
                linkDefinition("branch-a", "addBranch", "addA"),
                linkDefinition("branch-b", "addBranch", "addB"),
                linkDefinition("a-converge", "addA", "addConverge"),
                linkDefinition("b-converge", "addB", "addConverge"),
                linkDefinition("exit-converge", "addConverge", "next")
        ));
    }

    private WorkflowNodeDefinition nodeDefinition(String nodeKey) {
        return nodeDefinition(nodeKey, "user:add-signer-1");
    }

    private WorkflowNodeDefinition nodeDefinition(String nodeKey, String participantPolicyText) {
        return nodeDefinition(nodeKey, WorkflowNodeType.APPROVAL, participantPolicyText);
    }

    private WorkflowNodeDefinition nodeDefinition(String nodeKey, WorkflowNodeType nodeType,
                                                  String participantPolicyText) {
        WorkflowNodeDefinition definition = new WorkflowNodeDefinition();
        definition.setNodeKey(nodeKey);
        definition.setNodeType(nodeType);
        definition.setApprovalMode(WorkflowApprovalMode.ALL);
        definition.setParticipantPolicyText(participantPolicyText);
        definition.setAllowReject(true);
        definition.setAllowRollback(true);
        definition.setAllowAddSign(true);
        definition.setWarningDurationMinutes(30);
        definition.setOvertimeDurationMinutes(60);
        definition.setNodeConfigText("{\"source\":\"test\"}");
        return definition;
    }

    private WorkflowLinkDefinition linkDefinition(String routeKey, String sourceNodeKey, String targetNodeKey) {
        WorkflowLinkDefinition definition = new WorkflowLinkDefinition();
        definition.setRouteKey(routeKey);
        definition.setSourceNodeKey(sourceNodeKey);
        definition.setTargetNodeKey(targetNodeKey);
        definition.setDefaultRoute(false);
        return definition;
    }

    private WorkflowTaskActionService serviceWithPlugin(WorkflowRuntimePlugin plugin) {
        return new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                actionPolicyService,
                progressionService,
                Optional.empty(),
                null,
                null,
                new WorkflowRuntimePluginDispatcher(List.of(plugin)),
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(mock(WorkflowAutomaticApprovalService.class)));
    }

    private WorkflowTaskActionService serviceWithDelegationCompletionNotice() {
        WorkflowDelegationCompletionNoticeService completionNoticeService =
                new WorkflowDelegationCompletionNoticeService(taskDao, eventDao, eventFactory);
        return new WorkflowTaskActionService(taskDao,
                instanceDao,
                nodeDao,
                routeDao,
                eventDao,
                eventFactory,
                approvalTaskPolicyService,
                actionPolicyService,
                progressionService,
                Optional.empty(),
                null,
                completionNoticeService,
                null,
                taskEvaluator,
                taskSpecifications,
                checkResultDao, WorkflowTestSupport.provider(mock(WorkflowAutomaticApprovalService.class)));
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

    private WorkflowTaskActionRequest request(String taskId,
                                              String operatorId,
                                              String targetAssigneeId,
                                              WorkflowRejectResubmitMode rejectResubmitMode,
                                              String reason,
                                              Instant operatedAt) {
        return WorkflowTaskActionRequest.builder(taskId, operatorId)
                .targetAssigneeId(targetAssigneeId)
                .rejectResubmitMode(rejectResubmitMode)
                .reason(reason)
                .operatedAt(operatedAt)
                .build();
    }

    private WorkflowTaskActionRequest request(String taskId,
                                              String operatorId,
                                              String targetAssigneeId,
                                              WorkflowAddSignMode addSignMode,
                                              WorkflowAddSignSegment addSignSegment,
                                              WorkflowRejectResubmitMode rejectResubmitMode,
                                              String reason,
                                              Instant operatedAt) {
        return WorkflowTaskActionRequest.builder(taskId, operatorId)
                .targetAssigneeId(targetAssigneeId)
                .addSignMode(addSignMode)
                .addSignSegment(addSignSegment)
                .rejectResubmitMode(rejectResubmitMode)
                .reason(reason)
                .operatedAt(operatedAt)
                .build();
    }

    private WorkflowTaskActionRequest request(String taskId,
                                              String operatorId,
                                              String targetAssigneeId,
                                              WorkflowAddSignMode addSignMode,
                                              WorkflowAddSignSegment addSignSegment,
                                              WorkflowRejectResubmitMode rejectResubmitMode,
                                              String reason,
                                              Instant operatedAt,
                                              String selectedRouteKey,
                                              String selectedReason) {
        return WorkflowTaskActionRequest.builder(taskId, operatorId)
                .targetAssigneeId(targetAssigneeId)
                .addSignMode(addSignMode)
                .addSignSegment(addSignSegment)
                .rejectResubmitMode(rejectResubmitMode)
                .reason(reason)
                .operatedAt(operatedAt)
                .selectedRoute(selectedRouteKey, selectedReason)
                .build();
    }

    private WorkflowTaskActionRequest request(String taskId,
                                              String operatorId,
                                              String targetAssigneeId,
                                              WorkflowAddSignMode addSignMode,
                                              WorkflowAddSignSegment addSignSegment,
                                              WorkflowRejectResubmitMode rejectResubmitMode,
                                              String reason,
                                              Instant operatedAt,
                                              String selectedRouteKey,
                                              String selectedReason,
                                              List<WorkflowManualRouteSelection> manualRouteSelections,
                                              String semanticJson,
                                              String layoutJson) {
        return WorkflowTaskActionRequest.builder(taskId, operatorId)
                .targetAssigneeId(targetAssigneeId)
                .addSignMode(addSignMode)
                .addSignSegment(addSignSegment)
                .rejectResubmitMode(rejectResubmitMode)
                .reason(reason)
                .operatedAt(operatedAt)
                .selectedRoute(selectedRouteKey, selectedReason)
                .manualRouteSelections(manualRouteSelections)
                .designerSnapshot(semanticJson, layoutJson)
                .build();
    }

    private WorkflowNodeInstance node(WorkflowApprovalMode mode, Integer ratio) {
        WorkflowNodeInstance node = node("node-1", "approve", WorkflowNodeType.APPROVAL, WorkflowNodeStatus.ACTIVE);
        node.setApprovalMode(mode);
        node.setApprovalRatio(ratio);
        return node;
    }

    private WorkflowNodeInstance node(String id, String nodeKey, WorkflowNodeType nodeType, WorkflowNodeStatus status) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setTenantId("tenant-1");
        node.setVersion(2);
        node.setInstanceId("instance-1");
        node.setNodeKey(nodeKey);
        node.setNodeRunId(nodeKey + ":1");
        node.setNodeType(nodeType);
        node.setNodeStatus(status);
        return node;
    }

    private WorkflowRouteInstance route(String id, String source, String target, WorkflowRouteStatus status) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(id);
        route.setTenantId("tenant-1");
        route.setVersion(4);
        route.setInstanceId("instance-1");
        route.setRouteKey(source + "-" + target);
        route.setRouteRunId(source + "-" + target + ":1");
        route.setSourceNodeKey(source);
        route.setTargetNodeKey(target);
        route.setRouteStatus(status);
        return route;
    }

    private WorkflowRouteInstance scopedRoute(String id, String source, String target, WorkflowRouteStatus status) {
        WorkflowRouteInstance route = route(id, source, target, status);
        route.setBranchNodeKey("branch");
        route.setBranchRunId("branch:1");
        route.setConvergeNodeKey("converge");
        route.setConvergeRunId("converge:1");
        route.setRouteDepth(1);
        dirtyRoute(route);
        return route;
    }

    private void dirtyRoute(WorkflowRouteInstance route) {
        route.setRouteReason(WorkflowRouteReason.MANUAL_SELECTED);
        route.setConditionMatched(true);
        route.setSelectedBy("selector-1");
        route.setSelectedAt(Instant.parse("2026-06-05T02:00:00Z"));
        route.setSelectedReason("choose branch");
        route.setArrivedAt(Instant.parse("2026-06-05T02:30:00Z"));
        route.setClosedByRouteId("closed-route");
        route.setClosedReason("closed");
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setVersion(5);
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        return instance;
    }
}
