package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowRuntimeReadFacadeTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowTaskActionAvailabilityService availabilityService =
            mock(WorkflowTaskActionAvailabilityService.class);
    private final WorkflowActionPolicyService actionPolicyService = mock(WorkflowActionPolicyService.class);
    private final ModuleRecordFacts facts = mock(ModuleRecordFacts.class);
    private final WorkflowConditionService conditions = new WorkflowConditionService(facts);
    private final WorkflowRuntimeReadFacade facade = readFacade(WorkflowUserTitleResolver.NONE, WorkflowRecordSummaryResolver.NONE);

    @Test
    void shouldLoadRuntimeRenderBundle() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance node = node("node-1", "approve");
        WorkflowRouteInstance route = route();
        route.setSelectedReason("choose manual route");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(node));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(route));

        WorkflowRuntimeRenderBundle bundle = facade.renderBundle("instance-1");

        assertThat(bundle.mode()).isEqualTo("RUNTIME");
        assertThat(bundle.instance()).isSameAs(instance);
        assertThat(bundle.nodes()).containsExactly(node);
        assertThat(bundle.routes()).containsExactly(route);
        assertThat(bundle.routes().getFirst().getSelectedReason()).isEqualTo("choose manual route");
        verify(actionPolicyService).requireRecordView(instance);
    }

    @Test
    void shouldExposeManualBranchCandidatesFromDirectRuntimeRoutes() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance approve = node("node-approve", "approve");
        WorkflowNodeInstance manual = branch("node-manual", "manualBranch", WorkflowRouteMode.MANUAL,
                "approve", true, "2026-06-05T01:00:00Z");
        WorkflowNodeInstance auto = branch("node-auto", "autoBranch", WorkflowRouteMode.AUTO,
                null, false, "2026-06-05T00:30:00Z");
        WorkflowNodeInstance left = node("node-left", "leftTask");
        left.setNodeType(WorkflowNodeType.TASK);
        WorkflowNodeInstance right = node("node-right", "rightTask");
        right.setNodeType(WorkflowNodeType.TASK);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "manualBranch", "leftTask",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:01:00Z");
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "manualBranch", "rightTask",
                WorkflowRouteStatus.INEFFECTIVE, true, "2026-06-05T01:02:00Z");
        WorkflowRouteInstance canceledRoute = route("route-canceled", "canceledRoute", "manualBranch", "rightTask",
                WorkflowRouteStatus.CANCELED, false, "2026-06-05T01:03:00Z");
        WorkflowRouteInstance closedRoute = route("route-closed", "closedRoute", "manualBranch", "rightTask",
                WorkflowRouteStatus.CLOSED, false, "2026-06-05T01:04:00Z");
        WorkflowRouteInstance droppedRoute = route("route-dropped", "droppedRoute", "manualBranch", "rightTask",
                WorkflowRouteStatus.DROPPED, false, "2026-06-05T01:05:00Z");
        WorkflowRouteInstance nestedRoute = route("route-nested", "nestedRoute", "leftTask", "rightTask",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:06:00Z");
        WorkflowRouteInstance autoRoute = route("route-auto", "autoRoute", "autoBranch", "leftTask",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T00:31:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(approve, auto, manual, left, right));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(leftRoute, rightRoute, canceledRoute, closedRoute, droppedRoute, nestedRoute,
                        autoRoute));

        List<WorkflowManualBranchCandidateView> views = facade.manualBranchCandidates("instance-1");

        assertThat(views).hasSize(1);
        WorkflowManualBranchCandidateView view = views.getFirst();
        assertThat(view.branchNodeKey()).isEqualTo("manualBranch");
        assertThat(view.routeMode()).isEqualTo(WorkflowRouteMode.MANUAL);
        assertThat(view.selectorNodeKey()).isEqualTo("approve");
        assertThat(view.requireManualSelectionReason()).isTrue();
        assertThat(view.candidates()).extracting(WorkflowManualBranchCandidateView.Candidate::routeKey)
                .containsExactly("leftRoute", "rightRoute");
        assertThat(view.candidates().getFirst().routeId()).isEqualTo("route-left");
        assertThat(view.candidates().getFirst().targetNodeKey()).isEqualTo("leftTask");
        assertThat(view.candidates().getFirst().targetNodeType()).isEqualTo(WorkflowNodeType.TASK);
        assertThat(view.candidates().getFirst().routeStatus()).isEqualTo(WorkflowRouteStatus.CANDIDATE);
        assertThat(view.candidates().getFirst().defaultRoute()).isFalse();
        assertThat(view.candidates().get(1).defaultRoute()).isTrue();
        verify(actionPolicyService).requireRecordView(instance);
    }

    @Test
    void undecidedManualCandidatesUseCurrentFactsAndFrozenReadableRouteTitles() {
        var instance = instance("instance-1");
        instance.setSemanticJson("{\"links\":[{\"routeKey\":\"large\",\"title\":\"大额采购\"}]}");
        var manual = branch("manual", "purchaseKind", WorkflowRouteMode.MANUAL, "approve", true,
                "2026-06-05T01:00:00Z");
        manual.setNodeTitle("采购分类");
        var manager = node("manager", "manager"); manager.setNodeTitle("经理审批");
        var ordinary = node("ordinary", "ordinary"); ordinary.setNodeTitle("普通审批");
        var large = route("large", "large", "purchaseKind", "manager", WorkflowRouteStatus.CANDIDATE,
                false, "2026-06-05T01:01:00Z");
        large.setConditionExpression("{amount} >= 100"); large.setConditionMatched(false);
        var fallback = route("fallback", "default", "purchaseKind", "ordinary", WorkflowRouteStatus.CANDIDATE,
                true, "2026-06-05T01:02:00Z");
        fallback.setConditionExpression("broken default expression is ignored");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(manual, manager, ordinary));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(large, fallback));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));

        var view = facade.manualBranchCandidates("instance-1").getFirst();

        assertThat(view.branchTitle()).isEqualTo("采购分类");
        assertThat(view.candidates().getFirst().title()).isEqualTo("大额采购");
        assertThat(view.candidates().getFirst().targetNodeTitle()).isEqualTo("经理审批");
        assertThat(view.candidates().getFirst().conditionMatched()).isTrue();
        assertThat(view.candidates().getFirst().recommended()).isTrue();
        assertThat(view.candidates().get(1).title()).isEqualTo("普通审批");
        assertThat(view.candidates().get(1).conditionMatched()).isFalse();
        assertThat(view.candidates().get(1).recommended()).isFalse();
        assertThat(large.getConditionMatched()).isFalse(); // Advice never changes the frozen execution fact.
        var order = inOrder(actionPolicyService, facts);
        order.verify(actionPolicyService).requireRecordView(instance);
        order.verify(facts).read("sales.contract", "record-1");

        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 20));
        var revised = facade.manualBranchCandidates("instance-1").getFirst().candidates();
        assertThat(revised.getFirst().conditionMatched()).isFalse();
        assertThat(revised.get(1).recommended()).isTrue();

        large.setConditionExpression("{amount} >");
        var invalid = facade.manualBranchCandidates("instance-1").getFirst().candidates();
        assertThat(invalid.getFirst().conditionMatched()).isNull();
        assertThat(invalid).allMatch(candidate -> !candidate.recommended());
    }

    @Test
    void decidedManualRoutesKeepFrozenConditionFactsWithoutReadingBusinessAgain() {
        var instance = instance("instance-1"); instance.setSemanticJson("invalid old snapshot");
        var manual = branch("manual", "manualBranch", WorkflowRouteMode.MANUAL, "approve", true,
                "2026-06-05T01:00:00Z");
        var target = node("target", "target"); target.setNodeTitle("已选目标");
        var selected = route("selected", "selected", "manualBranch", "target", WorkflowRouteStatus.EFFECTIVE,
                false, "2026-06-05T01:01:00Z");
        selected.setConditionExpression("broken condition must not be reevaluated"); selected.setConditionMatched(true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(manual, target));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(selected));

        var candidate = facade.manualBranchCandidates("instance-1").getFirst().candidates().getFirst();

        assertThat(candidate.title()).isEqualTo("已选目标");
        assertThat(candidate.conditionMatched()).isTrue();
        assertThat(candidate.recommended()).isFalse();
        verifyNoInteractions(facts);
    }

    @Test
    void runtimeCandidatePermissionsPrecedeBusinessFactAndGraphReads() {
        var instance = instance("instance-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        doThrow(new PlatformException("view denied")).when(actionPolicyService).requireRecordView(instance);
        assertThatThrownBy(() -> facade.manualBranchCandidates("instance-1")).hasMessage("view denied");
        verifyNoInteractions(facts, nodeDao, routeDao);
    }

    @Test
    void runtimeTaskChoicesFollowAutoFactsAndAdvanceConsecutiveManualFrontiersWithoutMutatingRuntime() {
        var instance = instance("instance-1");
        var approve = node("node-1", "approve");
        var automatic = branch("auto", "auto", WorkflowRouteMode.AUTO, null, false, "2026-06-05T01:00:00Z");
        automatic.setNodeStatus(WorkflowNodeStatus.WAITING);
        var first = branch("first", "first", WorkflowRouteMode.MANUAL, "approve", false, "2026-06-05T01:01:00Z");
        first.setNodeStatus(WorkflowNodeStatus.WAITING);
        var next = branch("next", "next", WorkflowRouteMode.MANUAL, "approve", false, "2026-06-05T01:02:00Z");
        next.setNodeStatus(WorkflowNodeStatus.WAITING);
        var unreachable = branch("unreachable", "unreachable", WorkflowRouteMode.MANUAL, "approve", false, "2026-06-05T01:03:00Z");
        unreachable.setNodeStatus(WorkflowNodeStatus.WAITING);
        var target = node("target", "target"); target.setNodeStatus(WorkflowNodeStatus.WAITING);
        var initial = route("initial", "initial", "approve", "auto", WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:00:00Z");
        var match = route("match", "match", "auto", "first", WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:01:00Z");
        match.setConditionExpression("{amount} >= 100");
        var fallback = route("fallback", "fallback", "auto", "unreachable", WorkflowRouteStatus.CANDIDATE, true, "2026-06-05T01:02:00Z");
        var firstChoice = route("firstChoice", "firstChoice", "first", "next", WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:03:00Z");
        var nextChoice = route("nextChoice", "nextChoice", "next", "target", WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:04:00Z");
        var unreachableChoice = route("unreachableChoice", "unreachableChoice", "unreachable", "target", WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:05:00Z");
        var task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(approve, automatic, first, next, unreachable, target));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(initial, match, fallback, firstChoice, nextChoice, unreachableChoice));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(task));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));

        assertThat(facade.manualBranchCandidates("instance-1")).extracting(WorkflowManualBranchCandidateView::branchNodeKey)
                .containsExactly("first");
        assertThat(facade.manualBranchCandidatePrechecks("instance-1", "user-1"))
                .extracting(WorkflowManualBranchCandidatePrecheckView::branchNodeKey).containsExactly("first");
        assertThat(facade.manualBranchCandidates("instance-1", "task-1", List.of(), "user-1"))
                .extracting(WorkflowManualBranchCandidateView::branchNodeKey).containsExactly("first");
        var expanded = facade.manualBranchCandidates("instance-1", "task-1",
                List.of(new WorkflowManualRouteSelection("first", "firstChoice", null)), "user-1");
        assertThat(expanded).extracting(WorkflowManualBranchCandidateView::branchNodeKey).containsExactly("first", "next");
        assertThat(expanded.getFirst().selectionPending()).isFalse();
        assertThat(expanded.get(1).selectionPending()).isTrue();
        assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(approve.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(first.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(firstChoice.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANDIDATE);
    }

    @Test
    void aNonFinalVoteDoesNotRequireLaterManualChoicesOrReadBusinessFacts() {
        var instance = instance("instance-1");
        var approve = node("node-1", "approve"); approve.setApprovalMode(WorkflowApprovalMode.ALL);
        var first = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        var other = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.findById("task-1")).thenReturn(first);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(approve));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(first, other));

        assertThat(facade.manualBranchCandidates("instance-1", "task-1", List.of(), "user-1")).isEmpty();
        verifyNoInteractions(facts);
        assertThat(first.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
    }

    @Test
    void taskPlanningRejectsForeignTasksAndUnauthorizedOperatorsBeforeFactAccess() {
        var instance = instance("instance-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        var foreign = task("foreign", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        foreign.setInstanceId("other-instance");
        when(taskDao.findById("foreign")).thenReturn(foreign);
        assertThatThrownBy(() -> facade.manualBranchCandidates("instance-1", "foreign", List.of(), "user-1"))
                .hasMessageContaining("does not belong");
        var task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(taskDao.findById("task-1")).thenReturn(task);
        doThrow(new PlatformException("operator denied")).when(actionPolicyService).requireTaskOperator(task, "approve", "user-1");
        assertThatThrownBy(() -> facade.manualBranchCandidates("instance-1", "task-1", List.of(), "user-1"))
                .hasMessage("operator denied");
        verifyNoInteractions(facts, nodeDao, routeDao);
    }

    @Test
    void shouldPrecheckStartSelectorForMatchingAndNonMatchingOperator() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance manual = branch("node-manual", "manualBranch", WorkflowRouteMode.MANUAL,
                "START", true, "2026-06-05T01:00:00Z");
        WorkflowNodeInstance left = node("node-left", "leftTask");
        left.setNodeType(WorkflowNodeType.TASK);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "manualBranch", "leftTask",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:01:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(manual, left));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(leftRoute));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());

        List<WorkflowManualBranchCandidatePrecheckView> matching =
                facade.manualBranchCandidatePrechecks("instance-1", "starter-1");
        List<WorkflowManualBranchCandidatePrecheckView> mismatching =
                facade.manualBranchCandidatePrechecks("instance-1", "operator-2");

        assertThat(matching).hasSize(1);
        WorkflowManualBranchCandidatePrecheckView matchingView = matching.getFirst();
        assertThat(matchingView.selectorNodeKey()).isEqualTo("START");
        assertThat(matchingView.selectorResolvedUserId()).isEqualTo("starter-1");
        assertThat(matchingView.operatorId()).isEqualTo("starter-1");
        assertThat(matchingView.selectable()).isTrue();
        assertThat(matchingView.unselectableReason()).isNull();
        assertThat(matchingView.candidates().getFirst().selectable()).isTrue();
        assertThat(matchingView.candidates().getFirst().unselectableReason()).isNull();
        WorkflowManualBranchCandidatePrecheckView mismatchingView = mismatching.getFirst();
        assertThat(mismatchingView.selectable()).isFalse();
        assertThat(mismatchingView.unselectableReason()).isEqualTo("SELECTOR_NOT_OPERATOR");
        assertThat(mismatchingView.candidates().getFirst().selectable()).isFalse();
        assertThat(mismatchingView.candidates().getFirst().unselectableReason()).isEqualTo("SELECTOR_NOT_OPERATOR");
        verify(actionPolicyService, times(2)).requireRecordView(instance);
    }

    @Test
    void shouldPrecheckApprovalSelectorFromCompletedTaskActualProcessor() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance approve = node("node-approve", "approve");
        WorkflowNodeInstance manual = branch("node-manual", "manualBranch", WorkflowRouteMode.MANUAL,
                "approve", false, "2026-06-05T01:00:00Z");
        WorkflowNodeInstance left = node("node-left", "leftTask");
        left.setNodeType(WorkflowNodeType.TASK);
        WorkflowTask done = task("task-approve", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        done.setNodeInstanceId("node-approve");
        done.setAssigneeId("assignee-1");
        done.setOwnerId("owner-1");
        done.setActualProcessorId("processor-1");
        done.setCompletedAt(Instant.parse("2026-06-05T00:59:00Z"));
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "manualBranch", "leftTask",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:01:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(approve, manual, left));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(leftRoute));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(done));

        List<WorkflowManualBranchCandidatePrecheckView> views =
                facade.manualBranchCandidatePrechecks("instance-1", "processor-1");

        assertThat(views).hasSize(1);
        WorkflowManualBranchCandidatePrecheckView view = views.getFirst();
        assertThat(view.selectorNodeKey()).isEqualTo("approve");
        assertThat(view.selectorResolvedUserId()).isEqualTo("processor-1");
        assertThat(view.selectable()).isTrue();
        assertThat(view.candidates().getFirst().selectable()).isTrue();
    }

    @Test
    void shouldPrecheckDecidedRoutesAsUnselectable() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance manual = branch("node-manual", "manualBranch", WorkflowRouteMode.MANUAL,
                "START", false, "2026-06-05T01:00:00Z");
        WorkflowNodeInstance left = node("node-left", "leftTask");
        left.setNodeType(WorkflowNodeType.TASK);
        WorkflowNodeInstance right = node("node-right", "rightTask");
        right.setNodeType(WorkflowNodeType.TASK);
        WorkflowRouteInstance effective = route("route-left", "leftRoute", "manualBranch", "leftTask",
                WorkflowRouteStatus.EFFECTIVE, false, "2026-06-05T01:01:00Z");
        WorkflowRouteInstance ineffective = route("route-right", "rightRoute", "manualBranch", "rightTask",
                WorkflowRouteStatus.INEFFECTIVE, true, "2026-06-05T01:02:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(manual, left, right));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(effective, ineffective));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());

        List<WorkflowManualBranchCandidatePrecheckView> views =
                facade.manualBranchCandidatePrechecks("instance-1", "starter-1");

        assertThat(views).hasSize(1);
        WorkflowManualBranchCandidatePrecheckView view = views.getFirst();
        assertThat(view.selectable()).isFalse();
        assertThat(view.unselectableReason()).isEqualTo("ROUTE_ALREADY_DECIDED");
        assertThat(view.candidates()).extracting(WorkflowManualBranchCandidatePrecheckView.Candidate::selectable)
                .containsExactly(false, false);
        assertThat(view.candidates()).extracting(WorkflowManualBranchCandidatePrecheckView.Candidate::unselectableReason)
                .containsExactly("ROUTE_ALREADY_DECIDED", "ROUTE_ALREADY_DECIDED");
    }

    @Test
    void shouldLoadInstanceAvailableActionsWithTaskAndNodeContext() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node("node-1", "approve");
        WorkflowTaskAvailableAction reject = WorkflowTaskAvailableAction.of("reject", "驳回")
                .supportRejectReturnToMe(true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(node));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(task));
        when(availabilityService.availableActions("task-1", "user-1")).thenReturn(List.of(reject));

        List<WorkflowTaskAvailableAction> actions = facade.instanceAvailableActions("instance-1", "user-1");

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().taskId()).isEqualTo("task-1");
        assertThat(actions.getFirst().nodeKey()).isEqualTo("approve");
        assertThat(actions.getFirst().rejectResubmitModes()).containsExactly("restart", "return_to_me");
        assertThat(actions.getFirst().defaultRejectResubmitMode()).isEqualTo("restart");
        verify(actionPolicyService).requireRecordView(instance);
    }

    @Test
    void shouldNotExposeActionWhenOperatorIsNotTaskAssignee() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssigneeId("user-2");
        task.setDelegatedFromUserId("user-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(task));

        assertThat(facade.instanceAvailableActions("instance-1", "user-1")).isEmpty();
        verifyNoInteractions(availabilityService);
    }

    @Test
    void shouldBuildTodoWorkbenchCardsFromTasks() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        task.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance node = node("node-1", "visit");
        node.setNodeTitle("客户回访");
        node.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(task));
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        WorkflowRuntimeReadFacade facadeWithTitles = facadeWithTitles(Map.of("user-1", "处理人",
                "starter-1", "提交人"));

        List<WorkflowWorkbenchCard> cards = facadeWithTitles.todoCards("user-1", PageRequest.of(1, 20));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().boardType()).isEqualTo("TODO");
        assertThat(cards.getFirst().instanceId()).isEqualTo("instance-1");
        assertThat(cards.getFirst().taskId()).isEqualTo("task-1");
        assertThat(cards.getFirst().nodeKey()).isEqualTo("visit");
        assertThat(cards.getFirst().nodeTitle()).isEqualTo("客户回访");
        assertThat(cards.getFirst().currentAssigneeIds()).containsExactly("user-1");
        assertThat(cards.getFirst().currentAssigneeTitles()).containsExactly("处理人");
        assertThat(cards.getFirst().submitterUserId()).isEqualTo("starter-1");
        assertThat(cards.getFirst().submitterUserTitle()).isEqualTo("提交人");
        assertThat(cards.getFirst().originalAssigneeTitle()).isEqualTo("处理人");
        assertThat(cards.getFirst().overtimeStatus()).isEqualTo(WorkflowOvertimeStatus.WARNED);
    }

    @Test
    void shouldExposeAndFilterTodoWorkbenchCardsByAddSignSource() {
        WorkflowTask addSignTask = task("task-add", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        addSignTask.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask normalTask = task("task-normal", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        normalTask.setNodeInstanceId("node-normal");
        normalTask.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowNodeInstance addSignNode = node("node-1", "add-1");
        addSignNode.setAddedByAddSign(true);
        addSignNode.setAddSignSourceNodeKey("approve");
        addSignNode.setAddSignOperatorId("operator-1");
        addSignNode.setAddSignAt(Instant.parse("2026-06-05T00:30:00Z"));
        WorkflowNodeInstance normalNode = node("node-normal", "normal");
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(addSignTask, normalTask));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(addSignNode);
        when(nodeDao.findById("node-normal")).thenReturn(normalNode);

        List<WorkflowWorkbenchCard> addSignCards = facade.todoCards("user-1", PageRequest.of(1, 20),
                workbenchRequest(true, null));
        List<WorkflowWorkbenchCard> sourceCards = facade.todoCards("user-1", PageRequest.of(1, 20),
                workbenchRequest(null, "approve"));
        List<WorkflowWorkbenchCard> normalCards = facade.todoCards("user-1", PageRequest.of(1, 20),
                workbenchRequest(false, null));

        assertThat(addSignCards).hasSize(1);
        WorkflowWorkbenchCard card = addSignCards.getFirst();
        assertThat(card.taskId()).isEqualTo("task-add");
        assertThat(card.addedByAddSign()).isTrue();
        assertThat(card.addSignSourceNodeKey()).isEqualTo("approve");
        assertThat(card.addSignOperatorId()).isEqualTo("operator-1");
        assertThat(card.addSignAt()).isEqualTo(Instant.parse("2026-06-05T00:30:00Z"));
        assertThat(sourceCards).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-add");
        assertThat(normalCards).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-normal");
        assertThat(normalCards.getFirst().addedByAddSign()).isFalse();
        assertThat(normalCards.getFirst().addSignSourceNodeKey()).isNull();
    }

    @Test
    void shouldFilterWorkbenchCardsByStableRequestFields() {
        WorkflowTask matching = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        matching.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask skipped = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        skipped.setInstanceId("instance-2");
        skipped.setNodeInstanceId("node-2");
        skipped.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowInstance matchingInstance = instance("instance-1");
        matchingInstance.setDefinitionId("definition-1");
        matchingInstance.setWorkflowVersionId("version-1");
        matchingInstance.setStartedAt(Instant.parse("2026-06-05T00:00:00Z"));
        WorkflowInstance skippedInstance = instance("instance-2");
        skippedInstance.setModuleAlias("sales.order");
        skippedInstance.setRecordId("record-2");
        skippedInstance.setDefinitionId("definition-2");
        skippedInstance.setWorkflowVersionId("version-2");
        WorkflowNodeInstance matchingNode = node("node-1", "visit");
        matchingNode.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        WorkflowNodeInstance skippedNode = node("node-2", "approve");
        skippedNode.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(matching, skipped));
        when(instanceDao.findById("instance-1")).thenReturn(matchingInstance);
        when(instanceDao.findById("instance-2")).thenReturn(skippedInstance);
        when(nodeDao.findById("node-1")).thenReturn(matchingNode);
        when(nodeDao.findById("node-2")).thenReturn(skippedNode);
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest("sales.contract", "record-1",
                "definition-1", "version-1", null, WorkflowInstanceStatus.RUNNING, "visit",
                WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO, WorkflowAssignmentKind.NORMAL,
                WorkflowOvertimeStatus.WARNED, null, Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:00Z"), Instant.parse("2026-06-05T00:00:00Z"),
                Instant.parse("2026-06-05T02:00:00Z"), null, null, null, null, null, null, null, null,
                "starter-1", List.of());

        List<WorkflowWorkbenchCard> cards = facade.todoCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().taskId()).isEqualTo("task-1");
        assertThat(cards.getFirst().definitionId()).isEqualTo("definition-1");
        assertThat(cards.getFirst().workflowVersionId()).isEqualTo("version-1");
        assertThat(cards.getFirst().startedAt()).isEqualTo(Instant.parse("2026-06-05T00:00:00Z"));
        assertThat(cards.getFirst().submitterUserId()).isEqualTo("starter-1");
    }

    @Test
    void shouldSortWorkbenchCardsByWhitelistedField() {
        WorkflowTask earlier = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        earlier.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask later = task("task-2", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        later.setCreatedAt(Instant.parse("2026-06-05T03:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(later, earlier));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "visit"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("receivedAt", WorkflowSortDirection.ASC)));

        List<WorkflowWorkbenchCard> cards = facade.todoCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-1", "task-2");
    }

    @Test
    void shouldSortDoneWorkbenchCardsByActionCode() {
        WorkflowTask approved = task("task-approve", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        approved.setDecision("approve");
        approved.setActualProcessorId("user-1");
        WorkflowTask rejected = task("task-reject", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.REJECTED);
        rejected.setDecision("reject");
        rejected.setActualProcessorId("user-1");
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(rejected, approved));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("actionCode", WorkflowSortDirection.ASC)));

        List<WorkflowWorkbenchCard> cards = facade.doneCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId)
                .containsExactly("task-approve", "task-reject");
        assertThat(cards).extracting(WorkflowWorkbenchCard::actionCode).containsExactly("approve", "reject");
    }

    @Test
    void shouldSortWorkbenchCardsByAddSignAt() {
        WorkflowTask first = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        first.setCreatedAt(Instant.parse("2026-06-05T03:00:00Z"));
        WorkflowTask second = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        second.setNodeInstanceId("node-2");
        second.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowNodeInstance firstNode = node("node-1", "add-1");
        firstNode.setAddedByAddSign(true);
        firstNode.setAddSignSourceNodeKey("approve");
        firstNode.setAddSignAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowNodeInstance secondNode = node("node-2", "add-2");
        secondNode.setAddedByAddSign(true);
        secondNode.setAddSignSourceNodeKey("approve");
        secondNode.setAddSignAt(Instant.parse("2026-06-05T01:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(first, second));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(firstNode);
        when(nodeDao.findById("node-2")).thenReturn(secondNode);
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, true, "approve", List.of(new WorkflowWorkbenchSort("addSignAt", WorkflowSortDirection.ASC)));

        List<WorkflowWorkbenchCard> cards = facade.todoCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-2", "task-1");
    }

    @Test
    void shouldSortWorkbenchCardsByAddSignIdentityFields() {
        WorkflowTask normal = task("task-normal", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        normal.setCreatedAt(Instant.parse("2026-06-05T03:00:00Z"));
        WorkflowTask operatorA = task("task-operator-a", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        operatorA.setNodeInstanceId("node-add-a");
        operatorA.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowTask operatorB = task("task-operator-b", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        operatorB.setNodeInstanceId("node-add-b");
        operatorB.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowNodeInstance normalNode = node("node-1", "approve");
        WorkflowNodeInstance addByA = node("node-add-a", "add-a");
        addByA.setAddedByAddSign(true);
        addByA.setAddSignSourceNodeKey("approve");
        addByA.setAddSignOperatorId("operator-a");
        WorkflowNodeInstance addByB = node("node-add-b", "add-b");
        addByB.setAddedByAddSign(true);
        addByB.setAddSignSourceNodeKey("approve");
        addByB.setAddSignOperatorId("operator-b");
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(normal, operatorA, operatorB));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(normalNode);
        when(nodeDao.findById("node-add-a")).thenReturn(addByA);
        when(nodeDao.findById("node-add-b")).thenReturn(addByB);
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("addedByAddSign", WorkflowSortDirection.DESC),
                        new WorkflowWorkbenchSort("addSignSourceNodeKey", WorkflowSortDirection.ASC),
                        new WorkflowWorkbenchSort("addSignOperatorId", WorkflowSortDirection.DESC)));

        List<WorkflowWorkbenchCard> cards = facade.todoCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId)
                .containsExactly("task-operator-b", "task-operator-a", "task-normal");
    }

    @Test
    void shouldRejectUnsupportedWorkbenchSortField() {
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of());
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("rawSql", WorkflowSortDirection.ASC)));

        assertThatThrownBy(() -> facade.todoCards("user-1", PageRequest.of(1, 20), request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow workbench sort field");
    }

    @Test
    void shouldBuildTodoWorkbenchCardForPrincipalCanProcessDelegatedTask() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setAssigneeId("delegate-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("delegate-1");
        task.setPrincipalCanProcess(true);
        task.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(task));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));

        List<WorkflowWorkbenchCard> cards = facade.todoCards("principal-1", PageRequest.of(1, 20));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().assignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
        assertThat(cards.getFirst().originalAssigneeId()).isEqualTo("principal-1");
        assertThat(cards.getFirst().delegatedFromUserId()).isEqualTo("principal-1");
        assertThat(cards.getFirst().delegatedToUserId()).isEqualTo("delegate-1");
        assertThat(cards.getFirst().principalCanProcess()).isTrue();
        assertThat(cards.getFirst().currentAssigneeIds()).containsExactly("delegate-1", "principal-1");
        assertThat(cards.getFirst().delegationTaskCount()).isNull();
    }

    @Test
    void shouldSortWorkbenchCardsByDelegationIdentityFields() {
        WorkflowTask first = delegatedTodo("task-1", "principal-a", "source-b", "delegate-b", false);
        WorkflowTask second = delegatedTodo("task-2", "principal-a", "source-b", "delegate-b", true);
        WorkflowTask third = delegatedTodo("task-3", "principal-a", "source-b", "delegate-a", false);
        WorkflowTask fourth = delegatedTodo("task-4", "principal-a", "source-a", "delegate-z", true);
        WorkflowTask fifth = delegatedTodo("task-5", "principal-b", "source-z", "delegate-a", true);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(first, second, third, fourth, fifth));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("originalAssigneeId", WorkflowSortDirection.ASC),
                        new WorkflowWorkbenchSort("delegatedFromUserId", WorkflowSortDirection.DESC),
                        new WorkflowWorkbenchSort("delegatedToUserId", WorkflowSortDirection.ASC),
                        new WorkflowWorkbenchSort("principalCanProcess", WorkflowSortDirection.DESC)));

        List<WorkflowWorkbenchCard> cards = facade.todoCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId)
                .containsExactly("task-3", "task-2", "task-1", "task-4", "task-5");
    }

    @Test
    void shouldBuildTrackingCardsFromInstances() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowTask todo = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(instance));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(todo));

        List<WorkflowWorkbenchCard> cards = facade.trackingCards("starter-1", PageRequest.of(1, 20));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().boardType()).isEqualTo("TRACKING");
        assertThat(cards.getFirst().taskId()).isNull();
        assertThat(cards.getFirst().currentAssigneeIds()).containsExactly("user-1");
    }

    @Test
    void shouldSortTrackingWorkbenchCardsByApprovalStatus() {
        WorkflowInstance approved = instance("instance-approved");
        approved.setApprovalStatus(WorkflowApprovalStatus.APPROVED);
        WorkflowInstance processing = instance("instance-processing");
        processing.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(approved, processing));
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("approvalStatus", WorkflowSortDirection.ASC)));

        List<WorkflowWorkbenchCard> cards = facade.trackingCards("starter-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::instanceId)
                .containsExactly("instance-processing", "instance-approved");
        assertThat(cards).extracting(WorkflowWorkbenchCard::approvalStatus)
                .containsExactly(WorkflowApprovalStatus.PROCESSING, WorkflowApprovalStatus.APPROVED);
    }

    @Test
    void shouldExposeAddSignFieldsOnTrackingCardsFromCurrentNode() {
        WorkflowInstance instance = instance("instance-1");
        instance.setCurrentNodeKeys("add-1");
        WorkflowNodeInstance addSignNode = node("node-add", "add-1");
        addSignNode.setAddedByAddSign(true);
        addSignNode.setAddSignSourceNodeKey("approve");
        addSignNode.setAddSignOperatorId("operator-1");
        addSignNode.setAddSignAt(Instant.parse("2026-06-05T00:30:00Z"));
        WorkflowTask todo = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(instance));
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(addSignNode));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(todo));

        List<WorkflowWorkbenchCard> cards = facade.trackingCards("starter-1", PageRequest.of(1, 20),
                workbenchRequest(true, "approve"));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().boardType()).isEqualTo("TRACKING");
        assertThat(cards.getFirst().nodeKey()).isEqualTo("add-1");
        assertThat(cards.getFirst().addedByAddSign()).isTrue();
        assertThat(cards.getFirst().addSignSourceNodeKey()).isEqualTo("approve");
        assertThat(cards.getFirst().addSignOperatorId()).isEqualTo("operator-1");
        assertThat(cards.getFirst().addSignAt()).isEqualTo(Instant.parse("2026-06-05T00:30:00Z"));
    }

    @Test
    void shouldBuildDelegationWorkbenchCardsWithDelegationTaskCount() {
        WorkflowTask first = delegatedTask("task-1", "delegate-1");
        first.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask second = delegatedTask("task-2", "delegate-2");
        second.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(first, second));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));

        List<WorkflowWorkbenchCard> cards = facade.delegationCards("principal-1", PageRequest.of(1, 20));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().boardType()).isEqualTo("DELEGATION");
        assertThat(cards.getFirst().delegationTaskCount()).isEqualTo(2);
        assertThat(cards.getFirst().currentAssigneeIds()).containsExactly("delegate-1", "delegate-2");
        assertThat(cards.getFirst().delegatedFromUserId()).isEqualTo("principal-1");
    }

    @Test
    void shouldSortDelegationWorkbenchCardsByDelegationTaskCount() {
        WorkflowTask one = delegatedTask("task-1", "delegate-1");
        one.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask manyA = delegatedTask("task-2", "delegate-2");
        manyA.setInstanceId("instance-2");
        manyA.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowTask manyB = delegatedTask("task-3", "delegate-3");
        manyB.setInstanceId("instance-2");
        manyB.setCreatedAt(Instant.parse("2026-06-05T03:00:00Z"));
        WorkflowInstance firstInstance = instance("instance-1");
        WorkflowInstance secondInstance = instance("instance-2");
        secondInstance.setRecordId("record-2");
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(one, manyA, manyB));
        when(instanceDao.findById("instance-1")).thenReturn(firstInstance);
        when(instanceDao.findById("instance-2")).thenReturn(secondInstance);
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("delegationTaskCount", WorkflowSortDirection.DESC)));

        List<WorkflowWorkbenchCard> cards = facade.delegationCards("principal-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::instanceId).containsExactly("instance-2", "instance-1");
        assertThat(cards).extracting(WorkflowWorkbenchCard::delegationTaskCount).containsExactly(2, 1);
    }

    @Test
    void shouldFilterNoticeCardsByReadStatusAndReturnReadStatusOnCard() {
        WorkflowTask unread = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        WorkflowTask read = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.NOTICED);
        read.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(unread, read));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "notice"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, WorkflowNoticeReadStatus.READ,
                null, null, null, null, null, null, null, null, null, null, null, null, List.of());

        List<WorkflowWorkbenchCard> cards = facade.noticeCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().taskId()).isEqualTo("notice-2");
        assertThat(cards.getFirst().readStatus()).isEqualTo(WorkflowNoticeReadStatus.READ);
    }

    @Test
    void shouldSortNoticeCardsByReadStatus() {
        WorkflowTask unread = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        unread.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        WorkflowTask read = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.NOTICED);
        read.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(unread, read));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "notice"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("readStatus", WorkflowSortDirection.DESC)));

        List<WorkflowWorkbenchCard> cards = facade.noticeCards("user-1", PageRequest.of(1, 20), request);

        assertThat(cards).extracting(WorkflowWorkbenchCard::taskId).containsExactly("notice-2", "notice-1");
        assertThat(cards).extracting(WorkflowWorkbenchCard::readStatus)
                .containsExactly(WorkflowNoticeReadStatus.READ, WorkflowNoticeReadStatus.UNREAD);
    }

    @Test
    void shouldDeriveDelegationCompletedNoticeSourceFromExplanationFields() {
        WorkflowTask notice = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        notice.setAssignmentKind(WorkflowAssignmentKind.TRANSFERRED);
        notice.setAssigneeId("principal-1");
        notice.setOriginalAssigneeId("principal-1");
        notice.setDelegatedFromUserId("principal-1");
        notice.setDelegatedToUserId("delegate-1");
        notice.setDelegationPolicyId("delegation-1");
        notice.setActualProcessorId("user-b");
        notice.setAssignmentSnapshotText("{\"sourceTaskId\":\"task-1\"}");
        notice.setCreatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        notice.setCompletedAt(Instant.parse("2026-06-05T01:59:00Z"));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(notice));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));

        List<WorkflowWorkbenchCard> cards = facade.noticeCards("principal-1", PageRequest.of(1, 20));

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().readStatus()).isEqualTo(WorkflowNoticeReadStatus.UNREAD);
        assertThat(cards.getFirst().noticeSourceType()).isEqualTo("DELEGATION_COMPLETED");
        assertThat(cards.getFirst().delegatedFromUserId()).isEqualTo("principal-1");
        assertThat(cards.getFirst().delegatedToUserId()).isEqualTo("delegate-1");
        assertThat(cards.getFirst().completedAt()).isEqualTo(Instant.parse("2026-06-05T01:59:00Z"));
    }

    @Test
    void shouldBuildTodoDoneAndNoticeStatsWithStableBuckets() {
        WorkflowTask normalTodo = task("task-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        normalTodo.setNodeInstanceId("node-normal");
        WorkflowTask warnedTodo = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        warnedTodo.setNodeInstanceId("node-warned");
        WorkflowNodeInstance normalNode = node("node-normal", "visit");
        WorkflowNodeInstance warnedNode = node("node-warned", "approve");
        warnedNode.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(normalTodo, warnedTodo));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-normal")).thenReturn(normalNode);
        when(nodeDao.findById("node-warned")).thenReturn(warnedNode);

        WorkflowWorkbenchStats todoStats = facade.workbenchStats("todo", "user-1");

        assertThat(todoStats.boardType()).isEqualTo("TODO");
        assertThat(todoStats.items()).extracting(WorkflowWorkbenchStatItem::code)
                .containsExactly("ALL", "NORMAL", "WARNED", "OVERDUE");
        assertThat(count(todoStats, "ALL")).isEqualTo(2);
        assertThat(count(todoStats, "WARNED")).isEqualTo(1);

        WorkflowTask done = task("task-3", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        done.setActualProcessorId("user-1");
        WorkflowTask rejected = task("task-4", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.REJECTED);
        rejected.setActualProcessorId("user-1");
        WorkflowTask transferred = task("task-5", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TRANSFERRED);
        transferred.setTransferredBy("user-1");
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(done, rejected, transferred));

        WorkflowWorkbenchStats doneStats = facade.workbenchStats("done", "user-1");

        assertThat(doneStats.items()).extracting(WorkflowWorkbenchStatItem::code)
                .containsExactly("ALL", "DONE", "REJECTED", "ROLLED_BACK", "TRANSFERRED");
        assertThat(count(doneStats, "ALL")).isEqualTo(3);
        assertThat(count(doneStats, "REJECTED")).isEqualTo(1);
        assertThat(count(doneStats, "TRANSFERRED")).isEqualTo(1);

        WorkflowTask unread = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        WorkflowTask read = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.NOTICED);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(unread, read));

        WorkflowWorkbenchStats noticeStats = facade.workbenchStats("notice", "user-1");

        assertThat(noticeStats.items()).extracting(WorkflowWorkbenchStatItem::code)
                .containsExactly("ALL", "UNREAD", "READ");
        assertThat(count(noticeStats, "UNREAD")).isEqualTo(1);
        assertThat(count(noticeStats, "READ")).isEqualTo(1);
    }

    @Test
    void shouldBuildTrackingAndDelegationStats() {
        WorkflowInstance running = instance("instance-1");
        WorkflowInstance completed = instance("instance-2");
        completed.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(running, completed));
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of());

        WorkflowWorkbenchStats trackingStats = facade.workbenchStats("tracking", "starter-1");

        assertThat(count(trackingStats, "RUNNING")).isEqualTo(1);
        assertThat(count(trackingStats, "COMPLETED")).isEqualTo(1);

        WorkflowTask delegated = delegatedTask("task-1", "delegate-1");
        WorkflowNodeInstance node = node("node-1", "approve");
        node.setOvertimeStatus(WorkflowOvertimeStatus.OVERDUE);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(delegated));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node);

        WorkflowWorkbenchStats delegationStats = facade.workbenchStats("delegation", "principal-1");

        assertThat(delegationStats.items()).extracting(WorkflowWorkbenchStatItem::code)
                .containsExactly("ALL", "NORMAL", "WARNED", "OVERDUE");
        assertThat(count(delegationStats, "ALL")).isEqualTo(1);
        assertThat(count(delegationStats, "OVERDUE")).isEqualTo(1);
    }

    @Test
    void shouldApplyWorkbenchStatsFiltersWithoutChangingNoticeBuckets() {
        WorkflowTask unread = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        WorkflowTask read = task("notice-2", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.NOTICED);
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(unread, read));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "notice"));
        WorkflowWorkbenchQueryRequest request = new WorkflowWorkbenchQueryRequest(null, null, null, null, null,
                null, null, null, null, null, null, WorkflowNoticeReadStatus.READ,
                null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new WorkflowWorkbenchSort("rawSql", WorkflowSortDirection.ASC)));

        WorkflowWorkbenchStats stats = facade.workbenchStats("notice", "user-1", request);

        assertThat(stats.items()).extracting(WorkflowWorkbenchStatItem::code)
                .containsExactly("ALL", "UNREAD", "READ");
        assertThat(count(stats, "ALL")).isEqualTo(1);
        assertThat(count(stats, "UNREAD")).isZero();
        assertThat(count(stats, "READ")).isEqualTo(1);
    }

    @Test
    void shouldRejectMissingInstance() {
        assertThatThrownBy(() -> facade.renderBundle("missing"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow instance not found");
    }

    @Test
    void shouldDelegateInstanceTaskAndEventQueries() {
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));

        facade.instanceTasks("instance-1");
        facade.instanceEvents("instance-1");

        verify(taskDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class));
        verify(eventDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
        verify(actionPolicyService, times(2)).requireRecordView(any(WorkflowInstance.class));
    }

    @Test
    void shouldExplainRuntimeAddSignNodesAndRoutesWithSourceNodeName() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance source = node("node-source", "approve");
        source.setNodeSnapshotText("{\"nodeName\":\"审批节点\"}");
        WorkflowNodeInstance addNode = node("node-add", "add-1");
        addNode.setNodeType(WorkflowNodeType.APPROVAL);
        addNode.setNodeStatus(WorkflowNodeStatus.WAITING);
        addNode.setAddedByAddSign(true);
        addNode.setAddSignSourceNodeKey("approve");
        addNode.setAddSignOperatorId("operator-1");
        addNode.setAddSignAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowNodeInstance normalNode = node("node-normal", "normal");
        WorkflowRouteInstance addRoute = route("route-add", "entry-add", "approve", "add-1",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:01:00Z");
        addRoute.setAddedByAddSign(true);
        addRoute.setAddSignSourceNodeKey("approve");
        addRoute.setAddSignOperatorId("operator-1");
        addRoute.setAddSignAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowRouteInstance normalRoute = route("route-normal", "normal-route", "normal", "next",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:02:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(source, addNode, normalNode));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(addRoute, normalRoute));

        List<WorkflowRuntimeAddSignExplanationView> views = facade.addSignExplanations("instance-1");

        assertThat(views).hasSize(2);
        WorkflowRuntimeAddSignExplanationView nodeView = views.getFirst();
        assertThat(nodeView.originType()).isEqualTo("ADD_SIGN");
        assertThat(nodeView.dimension()).isEqualTo("NODE");
        assertThat(nodeView.isAddSignRoute()).isFalse();
        assertThat(nodeView.nodeInstanceId()).isEqualTo("node-add");
        assertThat(nodeView.nodeKey()).isEqualTo("add-1");
        assertThat(nodeView.nodeType()).isEqualTo(WorkflowNodeType.APPROVAL);
        assertThat(nodeView.nodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(nodeView.addSignSourceNodeKey()).isEqualTo("approve");
        assertThat(nodeView.addSignSourceNodeName()).isEqualTo("审批节点");
        assertThat(nodeView.addSignOperatorId()).isEqualTo("operator-1");
        assertThat(nodeView.addSignAt()).isEqualTo(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowRuntimeAddSignExplanationView routeView = views.get(1);
        assertThat(routeView.dimension()).isEqualTo("ROUTE");
        assertThat(routeView.isAddSignRoute()).isTrue();
        assertThat(routeView.routeId()).isEqualTo("route-add");
        assertThat(routeView.routeKey()).isEqualTo("entry-add");
        assertThat(routeView.routeSourceNodeKey()).isEqualTo("approve");
        assertThat(routeView.routeTargetNodeKey()).isEqualTo("add-1");
        assertThat(routeView.routeStatus()).isEqualTo(WorkflowRouteStatus.CANDIDATE);
        assertThat(routeView.addSignSourceNodeKey()).isEqualTo("approve");
        assertThat(routeView.addSignSourceNodeName()).isEqualTo("审批节点");
        InOrder inOrder = inOrder(instanceDao, actionPolicyService, nodeDao, routeDao);
        inOrder.verify(instanceDao).findById("instance-1");
        inOrder.verify(actionPolicyService).requireRecordView(instance);
        inOrder.verify(nodeDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class));
        inOrder.verify(routeDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class));
    }

    @Test
    void shouldReturnNoRuntimeAddSignExplanationsWhenNoAddSignMarkers() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance normalNode = node("node-normal", "normal");
        WorkflowRouteInstance normalRoute = route("route-normal", "normal-route", "normal", "next",
                WorkflowRouteStatus.CANDIDATE, false, "2026-06-05T01:02:00Z");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(normalNode));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(normalRoute));

        assertThat(facade.addSignExplanations("instance-1")).isEmpty();

        verify(actionPolicyService).requireRecordView(instance);
    }

    @Test
    void shouldFallbackAddSignSourceNodeNameToKeyWhenSourceNodeNameMissing() {
        WorkflowInstance instance = instance("instance-1");
        WorkflowNodeInstance addNode = node("node-add", "add-1");
        addNode.setAddedByAddSign(true);
        addNode.setAddSignSourceNodeKey("approve");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(addNode));
        when(routeDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of());

        List<WorkflowRuntimeAddSignExplanationView> views = facade.addSignExplanations("instance-1");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().addSignSourceNodeName()).isEqualTo("approve");
    }

    @Test
    void protectedTitleSearchOwnsExactTotalAndStablePages() {
        var first = task("task-a", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        var second = task("task-b", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        var denied = task("task-secret", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        denied.setInstanceId("instance-secret");
        first.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z")); second.setCreatedAt(first.getCreatedAt()); denied.setCreatedAt(first.getCreatedAt());
        when(taskDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class))).thenReturn(List.of(second, denied, first));
        when(instanceDao.findById("instance-1")).thenReturn(instance("instance-1"));
        var restricted = instance("instance-secret");restricted.setRecordId("secret");
        when(instanceDao.findById("instance-secret")).thenReturn(restricted);
        when(nodeDao.findById("node-1")).thenReturn(node("node-1", "approve"));
        var summaries = mock(WorkflowRecordSummaryResolver.class);
        when(summaries.resolve(any())).thenAnswer(call->((WorkflowInstance)call.getArgument(0)).getRecordId().equals("secret")
                ? new WorkflowRecordSummary(null,"销售合同",false) : new WorkflowRecordSummary("合同 ***", "销售合同", true));
        var paging = readFacade(WorkflowUserTitleResolver.NONE, summaries);
        var one = paging.workbenchPage("todo", "user-1", PageRequest.of(1,1), WorkflowWorkbenchQueryRequest.empty(), "合同");
        var two = paging.workbenchPage("todo", "user-1", PageRequest.of(2,1), WorkflowWorkbenchQueryRequest.empty(), "合同");
        assertThat(one.page().getTotal()).isEqualTo(2);
        assertThat(one.page().getRecords()).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-a");
        assertThat(two.page().getRecords()).extracting(WorkflowWorkbenchCard::taskId).containsExactly("task-b");
        assertThat(paging.workbenchPage("todo", "user-1", PageRequest.of(1,10), WorkflowWorkbenchQueryRequest.empty(), "secret").page().getTotal()).isZero();
        verify(summaries,times(6)).resolve(any()); // two business records, once per response despite multiple tasks
    }

    private WorkflowInstance instance(String id) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setDefinitionId("definition-1");
        instance.setWorkflowVersionId("version-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setStartedBy("starter-1");
        instance.setStartedAt(Instant.parse("2026-06-05T00:00:00Z"));
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        instance.setCurrentNodeKeys("approve");
        return instance;
    }

    private WorkflowTask task(String id, WorkflowTaskKind kind, WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskKind(kind);
        task.setTaskStatus(status);
        task.setAssigneeId("user-1");
        task.setOriginalAssigneeId("user-1");
        task.setAssignmentKind(WorkflowAssignmentKind.NORMAL);
        return task;
    }

    private WorkflowTask delegatedTask(String id, String delegateId) {
        WorkflowTask task = task(id, WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setAssigneeId(delegateId);
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId(delegateId);
        task.setPrincipalCanProcess(false);
        task.setDelegationPolicyId("delegation-1");
        return task;
    }

    private WorkflowTask delegatedTodo(String id, String originalAssigneeId, String delegatedFromUserId,
                                       String delegatedToUserId, Boolean principalCanProcess) {
        WorkflowTask task = task(id, WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId(originalAssigneeId);
        task.setAssigneeId("user-1");
        task.setDelegatedFromUserId(delegatedFromUserId);
        task.setDelegatedToUserId(delegatedToUserId);
        task.setPrincipalCanProcess(principalCanProcess);
        task.setDelegationPolicyId("delegation-1");
        return task;
    }

    private WorkflowNodeInstance node(String id, String key) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setInstanceId("instance-1");
        node.setNodeKey(key);
        node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        node.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
        return node;
    }

    private WorkflowNodeInstance branch(String id, String key, WorkflowRouteMode routeMode, String selectorNodeKey,
                                        Boolean requireReason, String createdAt) {
        WorkflowNodeInstance node = node(id, key);
        node.setNodeType(WorkflowNodeType.BRANCH);
        node.setRouteMode(routeMode);
        node.setSelectorNodeKey(selectorNodeKey);
        node.setRequireManualSelectionReason(requireReason);
        node.setCreatedAt(Instant.parse(createdAt));
        return node;
    }

    private WorkflowRouteInstance route() {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId("route-1");
        route.setInstanceId("instance-1");
        return route;
    }

    private WorkflowRouteInstance route(String id, String key, String sourceNodeKey, String targetNodeKey,
                                        WorkflowRouteStatus status, Boolean defaultRoute, String createdAt) {
        WorkflowRouteInstance route = route();
        route.setId(id);
        route.setRouteKey(key);
        route.setSourceNodeKey(sourceNodeKey);
        route.setTargetNodeKey(targetNodeKey);
        route.setRouteStatus(status);
        route.setDefaultRoute(defaultRoute);
        route.setCreatedAt(Instant.parse(createdAt));
        return route;
    }

    private WorkflowWorkbenchQueryRequest workbenchRequest(Boolean addedByAddSign, String addSignSourceNodeKey) {
        return new WorkflowWorkbenchQueryRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                addedByAddSign, addSignSourceNodeKey, List.of());
    }

    private long count(WorkflowWorkbenchStats stats, String code) {
        return stats.items().stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .map(WorkflowWorkbenchStatItem::count)
                .orElseThrow();
    }

    private WorkflowRuntimeReadFacade facadeWithTitles(Map<String, String> userTitles) {
        return readFacade(userIds -> userTitles, WorkflowRecordSummaryResolver.NONE);
    }
    private WorkflowRuntimeReadFacade readFacade(WorkflowUserTitleResolver titles, WorkflowRecordSummaryResolver summaries) {
        return new WorkflowRuntimeReadFacade(instanceDao, taskDao, nodeDao, routeDao, eventDao, availabilityService,
                actionPolicyService, new WorkflowTaskAssignmentPolicyService(),
                WorkflowTestSupport.provider(titles), WorkflowTestSupport.provider(summaries), conditions);
    }

}
