package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowSubmitReadFacadeTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowDefinitionSelector selector = mock(WorkflowDefinitionSelector.class);
    private final WorkflowSubmitFacade submitFacade = mock(WorkflowSubmitFacade.class);
    private final WorkflowModuleRecordGuard recordGuard = mock(WorkflowModuleRecordGuard.class);
    private final ModuleRecordFacts facts = mock(ModuleRecordFacts.class);
    private final WorkflowConditionService conditions = new WorkflowConditionService(facts);
    private final WorkflowSubmitReadFacade facade = new WorkflowSubmitReadFacade(instanceDao, selector, submitFacade,
            List.of(recordGuard), conditions);

    @Test
    void shouldReturnCurrentInstanceStatusWhenInstanceExists() {
        WorkflowInstance instance = instance("instance-1");
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(instance));

        WorkflowSubmitStatusView status = facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        assertThat(status.displayStatus()).isEqualTo("PROCESSING");
        assertThat(status.instanceId()).isEqualTo("instance-1");
        assertThat(status.definition().definitionId()).isEqualTo("definition-1");
        verify(recordGuard).requireRecordAction("sales.contract", "record-1",
                net.ximatai.muyun.spring.common.platform.PlatformAction.VIEW.executionPolicy());
        verifyNoInteractions(selector, submitFacade);
    }

    @Test void onlyTheOriginalInitiatorCanPreviewARejectedRestart() {
        var instance = instance("rejected"); instance.setInstanceStatus(WorkflowInstanceStatus.REJECTED);
        instance.setApprovalStatus(WorkflowApprovalStatus.REJECTED); instance.setRejectResubmitMode(WorkflowRejectResubmitMode.RESTART);
        instance.setStartedBy("initiator");
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class))).thenReturn(List.of(instance));
        assertThat(facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1").withOperator("initiator")).canSubmit()).isTrue();
        assertThat(facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1").withOperator("other")).canSubmit()).isFalse();
        instance.setRejectResubmitMode(WorkflowRejectResubmitMode.RETURN_TO_ME);
        assertThat(facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1").withOperator("initiator")).canSubmit()).isFalse();
    }

    @Test
    void shouldReturnUnsubmittedStatusWhenDefinitionCanBeSelected() {
        WorkflowDefinitionSelection selection = selection();
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of());
        when(selector.select(any(WorkflowSubmitRequest.class))).thenReturn(selection);

        WorkflowSubmitStatusView status = facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        assertThat(status.displayStatus()).isEqualTo("UNSUBMITTED");
        assertThat(status.definition().definitionAlias()).isEqualTo("defaultApproval");
        verify(recordGuard).requireRecordAction("sales.contract", "record-1",
                net.ximatai.muyun.spring.common.platform.PlatformAction.VIEW.executionPolicy());
    }

    @Test
    void shouldReturnNoWorkflowStatusWhenApprovalDefinitionIsMissing() {
        when(instanceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of());
        when(selector.select(any(WorkflowSubmitRequest.class)))
                .thenThrow(new PlatformException("published workflow definition not found: sales.contract"));

        WorkflowSubmitStatusView status = facade.status(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        assertThat(status.displayStatus()).isEqualTo("NO_WORKFLOW");
        assertThat(status.errorMessage()).contains("published workflow definition not found");
    }

    @Test
    void shouldReturnSubmitPreviewViewFromSubmitFacade() {
        WorkflowSubmitPreview preview = new WorkflowSubmitPreview(selection(), draft());
        when(submitFacade.preview(any(WorkflowSubmitRequest.class))).thenReturn(preview);

        WorkflowSubmitPreviewView view = facade.preview(WorkflowSubmitRequest.approval("sales.contract", "record-1")
                .withOperator("user-1"));

        assertThat(view.mode()).isEqualTo("SUBMIT_PREVIEW");
        assertThat(view.definition().definitionAlias()).isEqualTo("defaultApproval");
        assertThat(view.instance().getId()).isEqualTo("instance-1");
    }

    @Test void previewExplainsActualDelegateAndOriginalApproverFromTheSameDraft() {
        var task = new WorkflowTask();task.setId("task");task.setAssigneeId("delegate");task.setOriginalAssigneeId("principal");
        task.setDelegatedFromUserId("principal");task.setDelegatedToUserId("delegate");task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        var draft = new WorkflowSubmitDraft(instance("instance-1"), List.of(),List.of(),List.of(task),List.of(),draft().activation());
        when(submitFacade.preview(any())).thenReturn(new WorkflowSubmitPreview(selection(),draft));
        WorkflowUserTitleResolver titles = ids -> { assertThat(ids).containsExactlyInAnyOrder("delegate","principal");return java.util.Map.of("delegate","代办人","principal","原审批人"); };
        var facade = new WorkflowSubmitReadFacade(instanceDao,selector,submitFacade,List.of(recordGuard),titles,conditions);
        var view = facade.preview(WorkflowSubmitRequest.approval("sales.contract","record-1").withOperator("user-1"));
        assertThat(view.tasks()).containsExactly(task);
        assertThat(view.taskViews().getFirst().assigneeTitle()).isEqualTo("代办人");
        assertThat(view.taskViews().getFirst().originalAssigneeTitle()).isEqualTo("原审批人");
    }

    @Test
    void submissionCandidatesKeepAllChoicesAndExplainCurrentConditionAdvice() {
        when(selector.select(any())).thenReturn(manualSelection("{amount} >= 100"));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));

        var view = facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1")).getFirst();

        assertThat(view.branchTitle()).isEqualTo("采购分类");
        assertThat(view.candidates()).extracting(WorkflowManualBranchCandidateView.Candidate::routeKey)
                .containsExactly("large", "default");
        var large = view.candidates().getFirst();
        assertThat(large.title()).isEqualTo("大额采购");
        assertThat(large.targetNodeTitle()).isEqualTo("经理审批");
        assertThat(large.conditionMatched()).isTrue();
        assertThat(large.recommended()).isTrue();
        assertThat(view.candidates().get(1).conditionMatched()).isFalse();
        assertThat(view.candidates().get(1).recommended()).isFalse();
        assertThat(view.candidates()).allMatch(candidate -> candidate.routeStatus() == WorkflowRouteStatus.CANDIDATE);
        var order = inOrder(recordGuard, facts);
        order.verify(recordGuard).beforeSubmit(any());
        order.verify(facts).read("sales.contract", "record-1");
    }

    @Test
    void defaultIsRecommendedOnlyWhenNoNonDefaultConditionMatches() {
        when(selector.select(any())).thenReturn(manualSelection("{amount} >= 100"));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 20));
        var candidates = facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1"))
                .getFirst().candidates();
        assertThat(candidates.getFirst().conditionMatched()).isFalse();
        assertThat(candidates.getFirst().recommended()).isFalse();
        assertThat(candidates.get(1).conditionMatched()).isFalse();
        assertThat(candidates.get(1).recommended()).isTrue();
    }

    @Test
    void blankConditionMatchesAndInvalidConditionDoesNotRecommendADefaultByMistake() {
        when(selector.select(any())).thenReturn(manualSelection(null));
        var blank = facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1"))
                .getFirst().candidates();
        assertThat(blank.getFirst().conditionMatched()).isTrue();
        assertThat(blank.getFirst().recommended()).isTrue();
        assertThat(blank.get(1).recommended()).isFalse();
        verifyNoInteractions(facts);

        when(selector.select(any())).thenReturn(manualSelection("{amount} >"));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 20));
        var invalid = facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1"))
                .getFirst().candidates();
        assertThat(invalid.getFirst().conditionMatched()).isNull();
        assertThat(invalid).allMatch(candidate -> !candidate.recommended());
    }

    @Test
    void submissionCandidatePermissionsAreCheckedBeforeSelectingOrReadingBusinessFacts() {
        doThrow(new PlatformException("submit denied")).when(recordGuard).beforeSubmit(any());
        assertThatThrownBy(() -> facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1")))
                .hasMessage("submit denied");
        verifyNoInteractions(selector, facts);
    }

    @Test
    void submissionOnlyAsksForTheManualBranchReachedThroughCurrentAutoConditions() {
        var base = manualSelection("{amount} >= 100");
        var automatic = new WorkflowNodeDefinition(); automatic.setNodeKey("auto"); automatic.setNodeType(WorkflowNodeType.BRANCH);
        automatic.setRouteMode(WorkflowRouteMode.AUTO);
        var unreachable = new WorkflowNodeDefinition(); unreachable.setNodeKey("unreachable"); unreachable.setNodeType(WorkflowNodeType.BRANCH);
        unreachable.setRouteMode(WorkflowRouteMode.MANUAL); unreachable.setSelectorNodeKey("start");
        var nodes = new java.util.ArrayList<>(base.nodes()); nodes.addAll(List.of(automatic, unreachable));
        var links = new java.util.ArrayList<>(base.links()); links.getFirst().setTargetNodeKey("auto");
        links.add(link("matching", "auto", "purchaseKind", "{amount} >= 100", false));
        links.add(link("otherwise", "auto", "unreachable", null, true));
        links.add(link("unreachableChoice", "unreachable", "ordinary", "broken condition must stay irrelevant", false));
        when(selector.select(any())).thenReturn(new WorkflowDefinitionSelection(base.definition(), base.version(), nodes, links));
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));

        var views = facade.manualBranches(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        assertThat(views).extracting(WorkflowManualBranchCandidateView::branchNodeKey).containsExactly("purchaseKind");
        assertThat(views.getFirst().selectionPending()).isTrue();
        assertThat(views.getFirst().candidates().getFirst().conditionMatched()).isTrue();
        verify(facts).read("sales.contract", "record-1");
    }

    @Test
    void consecutiveManualChoicesRevealNextFrontierWhileKeepingPreviousControls() {
        var base = manualSelection(null);
        var next = new WorkflowNodeDefinition(); next.setNodeKey("next"); next.setTitle("后续选择");
        next.setNodeType(WorkflowNodeType.BRANCH); next.setRouteMode(WorkflowRouteMode.MANUAL); next.setSelectorNodeKey("start");
        var nodes = new java.util.ArrayList<>(base.nodes()); nodes.add(next);
        var links = new java.util.ArrayList<>(base.links());
        links.stream().filter(link -> "large".equals(link.getRouteKey())).findFirst().orElseThrow().setTargetNodeKey("next");
        links.add(link("nextChoice", "next", "manager", null, false));
        when(selector.select(any())).thenReturn(new WorkflowDefinitionSelection(base.definition(), base.version(), nodes, links));

        var request = WorkflowSubmitRequest.approval("sales.contract", "record-1");
        assertThat(facade.manualBranches(request)).extracting(WorkflowManualBranchCandidateView::branchNodeKey)
                .containsExactly("purchaseKind");
        var reached = facade.manualBranches(request.withManualRouteSelections(
                List.of(new WorkflowManualRouteSelection("purchaseKind", "large", null))));
        assertThat(reached).extracting(WorkflowManualBranchCandidateView::branchNodeKey).containsExactly("purchaseKind", "next");
        assertThat(reached.getFirst().selectionPending()).isFalse();
        assertThat(reached.get(1).selectionPending()).isTrue();
        assertThat(facade.manualBranches(request.withManualRouteSelections(
                List.of(new WorkflowManualRouteSelection("purchaseKind", "default", null)))))
                .extracting(WorkflowManualBranchCandidateView::branchNodeKey).containsExactly("purchaseKind");
        verifyNoInteractions(facts);
    }

    private WorkflowLinkDefinition link(String key, String source, String target, String condition, boolean defaultRoute) {
        var link = new WorkflowLinkDefinition(); link.setRouteKey(key); link.setSourceNodeKey(source);
        link.setTargetNodeKey(target); link.setConditionExpression(condition); link.setDefaultRoute(defaultRoute);
        return link;
    }

    private WorkflowDefinitionSelection manualSelection(String condition) {
        var start = new WorkflowNodeDefinition(); start.setNodeKey("start"); start.setNodeType(WorkflowNodeType.START);
        var branch = new WorkflowNodeDefinition(); branch.setNodeKey("purchaseKind"); branch.setTitle("采购分类");
        branch.setNodeType(WorkflowNodeType.BRANCH); branch.setRouteMode(WorkflowRouteMode.MANUAL);
        branch.setSelectorNodeKey("start");
        var manager = new WorkflowNodeDefinition(); manager.setNodeKey("manager"); manager.setTitle("经理审批");
        manager.setNodeType(WorkflowNodeType.APPROVAL);
        var ordinary = new WorkflowNodeDefinition(); ordinary.setNodeKey("ordinary"); ordinary.setTitle("普通审批");
        ordinary.setNodeType(WorkflowNodeType.APPROVAL);
        var large = new WorkflowLinkDefinition(); large.setRouteKey("large"); large.setTitle("大额采购");
        large.setSourceNodeKey("purchaseKind"); large.setTargetNodeKey("manager"); large.setConditionExpression(condition);
        var fallback = new WorkflowLinkDefinition(); fallback.setRouteKey("default"); fallback.setSourceNodeKey("purchaseKind");
        fallback.setTargetNodeKey("ordinary"); fallback.setDefaultRoute(true);
        fallback.setConditionExpression("invalid ignored default condition");
        var initial = new WorkflowLinkDefinition(); initial.setRouteKey("initial"); initial.setSourceNodeKey("start");
        initial.setTargetNodeKey("purchaseKind");
        return new WorkflowDefinitionSelection(selection().definition(), selection().version(),
                List.of(start, branch, manager, ordinary), List.of(initial, large, fallback));
    }

    private WorkflowInstance instance(String id) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setDefinitionId("definition-1");
        instance.setWorkflowVersionId("version-1");
        instance.setVersionNo(1);
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setApprovalEnabled(Boolean.TRUE);
        instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        return instance;
    }

    private WorkflowDefinitionSelection selection() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("definition-1");
        definition.setAlias("defaultApproval");
        definition.setTitle("Default Approval");
        WorkflowVersion version = new WorkflowVersion();
        version.setId("version-1");
        version.setVersionNo(1);
        return new WorkflowDefinitionSelection(definition, version, List.of(), List.of());
    }

    private WorkflowSubmitDraft draft() {
        return new WorkflowSubmitDraft(instance("instance-1"), List.of(), List.of(), List.of(), List.of(),
                new WorkflowActivationResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false));
    }
}
