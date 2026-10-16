package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowModuleTaskRuntimeServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowBusinessTaskResolver specifications = mock(WorkflowBusinessTaskResolver.class);
    private final WorkflowModuleTaskEvaluator evaluator = mock(WorkflowModuleTaskEvaluator.class);
    private final WorkflowTaskActionFacade taskActionFacade = mock(WorkflowTaskActionFacade.class);
    private final WorkflowModuleTaskRuntimeService service = new WorkflowModuleTaskRuntimeService(
            taskDao, instanceDao, nodeDao, specifications, new WorkflowTaskAssignmentPolicyService(), new WorkflowActionPolicyService(), evaluator, taskActionFacade);

    @Test
    void shouldPrepareModuleTaskProcessBundle() {
        WorkflowTaskGuide guide = guide(WorkflowTaskGuideKind.EXECUTE_ACTION);
        WorkflowModuleTaskEvaluation evaluation = WorkflowModuleTaskEvaluation.manualConfirm(List.of(guide));
        mockContext(evaluation);

        WorkflowModuleTaskProcessBundle bundle = service.prepare("task-1", "user-1");

        assertThat(bundle.taskId()).isEqualTo("task-1");
        assertThat(bundle.instanceId()).isEqualTo("instance-1");
        assertThat(bundle.nodeKey()).isEqualTo("visit");
        assertThat(bundle.moduleAlias()).isEqualTo("sales.contract");
        assertThat(bundle.recordId()).isEqualTo("record-1");
        assertThat(bundle.completionPolicy()).isEqualTo(WorkflowModuleTaskCompletionPolicy.AFTER_ACTION_SUCCESS);
        assertThat(bundle.workflowTaskContext().workflowTaskId()).isEqualTo("task-1");
        assertThat(bundle.workflowTaskContext().completionPolicy()).isEqualTo(WorkflowModuleTaskCompletionPolicy.AFTER_ACTION_SUCCESS);
        assertThat(bundle.nextGuide()).isSameAs(guide);
    }

    @Test
    void shouldBlockCheckAndContinueWhenEvaluationFails() {
        mockContext(WorkflowModuleTaskEvaluation.failed("not ready", List.of(), List.of()));

        WorkflowModuleTaskContinueResult result = service.checkAndContinue("task-1", "user-1", "done");

        assertThat(result.continued()).isFalse();
        assertThat(result.processBundle().evaluation().failureMessage()).isEqualTo("not ready");
        verifyNoInteractions(taskActionFacade);
    }

    @Test
    void shouldCompleteBusinessTaskWhenEvaluationPassed() {
        mockContext(WorkflowModuleTaskEvaluation.passed(List.of(), List.of()));
        WorkflowTaskActionResult actionResult = mock(WorkflowTaskActionResult.class);
        when(taskActionFacade.execute(eq("complete"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "done".equals(request.reason()))))
                .thenReturn(actionResult);

        WorkflowModuleTaskContinueResult result = service.checkAndContinue("task-1", "user-1", "done");

        assertThat(result.continued()).isTrue();
        assertThat(result.actionResult()).isSameAs(actionResult);
        verify(taskActionFacade).execute(eq("complete"), argThat(request -> "task-1".equals(request.taskId())));
    }

    @Test
    void shouldPassSelectedRouteKeyWhenCheckAndContinuePassed() {
        mockContext(WorkflowModuleTaskEvaluation.passed(List.of(), List.of()));
        WorkflowTaskActionResult actionResult = mock(WorkflowTaskActionResult.class);
        when(taskActionFacade.execute(eq("complete"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "done".equals(request.reason())
                        && "leftRoute".equals(request.selectedRouteKey())
                        && "choose left".equals(request.selectedReason()))))
                .thenReturn(actionResult);

        WorkflowModuleTaskContinueResult result = service.checkAndContinue("task-1", "user-1", "done",
                "leftRoute", "choose left");

        assertThat(result.continued()).isTrue();
        assertThat(result.actionResult()).isSameAs(actionResult);
        verify(taskActionFacade).execute(eq("complete"), argThat(request ->
                "leftRoute".equals(request.selectedRouteKey())));
    }

    @Test
    void shouldPassManualRouteSelectionsWhenCheckAndContinuePassed() {
        mockContext(WorkflowModuleTaskEvaluation.passed(List.of(), List.of()));
        WorkflowTaskActionResult actionResult = mock(WorkflowTaskActionResult.class);
        when(taskActionFacade.execute(eq("complete"), argThat(request ->
                request.manualRouteSelections().size() == 2
                        && "branchA".equals(request.manualRouteSelections().get(0).branchNodeKey())
                        && "routeA1".equals(request.manualRouteSelections().get(0).routeKey())
                        && "choose A1".equals(request.manualRouteSelections().get(0).selectedReason())
                        && "branchB".equals(request.manualRouteSelections().get(1).branchNodeKey())
                        && "routeB2".equals(request.manualRouteSelections().get(1).routeKey()))))
                .thenReturn(actionResult);

        WorkflowModuleTaskContinueResult result = service.checkAndContinue("task-1", "user-1", "done",
                List.of(new WorkflowManualRouteSelection("branchA", "routeA1", "choose A1"),
                        new WorkflowManualRouteSelection("branchB", "routeB2", "choose B2")));

        assertThat(result.continued()).isTrue();
        assertThat(result.actionResult()).isSameAs(actionResult);
        verify(taskActionFacade).execute(eq("complete"), argThat(request ->
                request.manualRouteSelections().size() == 2));
    }

    @Test
    void shouldRejectNonAssigneeOperator() {
        WorkflowTask task = task();
        when(taskDao.findById("task-1")).thenReturn(task);

        assertThatThrownBy(() -> service.prepare("task-1", "other"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("operator is not assignee");
    }

    @Test
    void shouldRejectNodeWithoutTaskDefinition() {
        WorkflowTask task = task();
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node();
        node.setTaskDefinitionId(null);
        when(specifications.resolve(node)).thenThrow(new PlatformException("业务任务未配置完成策略"));
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);

        assertThatThrownBy(() -> service.prepare("task-1", "user-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("业务任务未配置完成策略");
    }

    private void mockContext(WorkflowModuleTaskEvaluation evaluation) {
        WorkflowTask task = task();
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node();
        WorkflowTaskDefinition definition = definition();
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(specifications.resolve(node)).thenReturn(new WorkflowBusinessTaskSpec(definition, List.of(), List.of()));
        when(evaluator.evaluate(instance, node, task, definition)).thenReturn(evaluation);
    }

    private WorkflowTask task() {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskKind(WorkflowTaskKind.BUSINESS);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setAssigneeId("user-1");
        return task;
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        return instance;
    }

    private WorkflowNodeInstance node() {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId("node-1");
        node.setNodeKey("visit");
        node.setNodeType(WorkflowNodeType.TASK);
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        node.setTaskDefinitionId("task-def-1");
        return node;
    }

    private WorkflowTaskDefinition definition() {
        WorkflowTaskDefinition definition = new WorkflowTaskDefinition();
        definition.setId("task-def-1");
        definition.setEnabled(true);
        definition.setManualConfirm(true);
        return definition;
    }

    private WorkflowTaskGuide guide(WorkflowTaskGuideKind kind) {
        WorkflowTaskGuide guide = new WorkflowTaskGuide();
        guide.setGuideKind(kind);
        return guide;
    }
}
