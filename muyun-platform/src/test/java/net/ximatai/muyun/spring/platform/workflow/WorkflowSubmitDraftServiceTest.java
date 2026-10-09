package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSubmitDraftServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowInstanceStateService instanceStateService = new WorkflowInstanceStateService();
    private final WorkflowSubmitDraftService service = new WorkflowSubmitDraftService(new WorkflowInstanceSnapshotFactory(instanceStateService, eventFactory),
                new WorkflowRuntimeActivationService(),
                instanceStateService,
                new WorkflowNodeInstanceStateService(),
                new WorkflowRouteInstanceStateService(),
                new WorkflowRouteRuntimeService(),
                new WorkflowRuntimeTaskFactory(eventFactory,
                java.util.Optional.empty(),
                WorkflowTestSupport.participants()),
                WorkflowTestSupport.facts());

    @Test
    void shouldBuildSubmitDraftUntilFirstApprovalBlock() {
        WorkflowSubmitDraft draft = service.build(definition(true), version(),
                List.of(node("start", WorkflowNodeType.START), node("approve", WorkflowNodeType.APPROVAL)),
                List.of(link("r1", "start", "approve")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(draft.instance().getCurrentNodeKeys()).isEqualTo("approve");
        assertThat(draft.instance().getSemanticJson()).isEqualTo("{\"nodes\":[\"approve\"]}");
        assertThat(draft.instance().getLayoutJson()).isEqualTo("{\"zoom\":1}");
        assertThat(draft.nodes()).hasSize(2);
        assertThat(draft.nodes()).extracting(WorkflowNodeInstance::getNodeStatus)
                .containsExactly(WorkflowNodeStatus.COMPLETED, WorkflowNodeStatus.ACTIVE);
        assertThat(draft.routes()).hasSize(1);
        assertThat(draft.routes().get(0).getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(draft.tasks()).hasSize(1)
                .first()
                .satisfies(task -> assertThat(task.getTaskKind()).isEqualTo(WorkflowTaskKind.APPROVAL));
        assertThat(draft.events()).extracting(WorkflowEvent::getEventType)
                .containsExactly(WorkflowEventType.INSTANCE_STARTED, WorkflowEventType.TASK_CREATED);
    }

    @Test
    void shouldMarkOnlyTraversedBranchRouteEffective() {
        WorkflowSubmitDraft draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        node("branch", WorkflowNodeType.BRANCH),
                        node("leftTask", WorkflowNodeType.TASK),
                        node("rightTask", WorkflowNodeType.TASK)),
                List.of(link("toBranch", "start", "branch"),
                        conditionLink("leftRoute", "branch", "leftTask", "false"),
                        defaultLink("rightRoute", "branch", "rightTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.activation().traversedRouteKeys()).containsExactly("toBranch", "rightRoute");
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("toBranch"))
                .first()
                .satisfies(route -> assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE));
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("rightRoute"))
                .first()
                .satisfies(route -> assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE));
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("rightRoute"))
                .first().satisfies(route -> {
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.DEFAULT_SELECTED);
                    assertThat(route.getConditionMatched()).isFalse();
                });
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("leftRoute"))
                .first()
                .satisfies(route -> assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE));
    }

    @Test
    void shouldUseSelectedRouteKeyForReachableInitialBranch() {
        WorkflowSubmitDraft draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        manualBranch("branch", "start", false),
                        node("leftTask", WorkflowNodeType.TASK),
                        node("rightTask", WorkflowNodeType.TASK)),
                List.of(link("toBranch", "start", "branch"),
                        link("leftRoute", "branch", "leftTask"),
                        defaultLink("rightRoute", "branch", "rightTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", "choose left");

        assertThat(draft.activation().traversedRouteKeys()).containsExactly("toBranch", "leftRoute");
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("leftRoute"))
                .first()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
                    assertThat(route.getSelectedBy()).isEqualTo("user-1");
                    assertThat(route.getSelectedReason()).isEqualTo("choose left");
                });
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("rightRoute"))
                .first()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED);
                    assertThat(route.getSelectedReason()).isNull();
                });
    }

    @Test
    void shouldUseStructuredSelectionsForMultipleReachableInitialManualBranches() {
        WorkflowSubmitDraft draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        node("split", WorkflowNodeType.MILESTONE),
                        manualBranch("branchA", "START", true),
                        manualBranch("branchB", "START", true),
                        node("taskA1", WorkflowNodeType.TASK),
                        node("taskA2", WorkflowNodeType.TASK),
                        node("taskB1", WorkflowNodeType.TASK),
                        node("taskB2", WorkflowNodeType.TASK)),
                List.of(link("toSplit", "start", "split"),
                        link("toBranchA", "split", "branchA"),
                        link("toBranchB", "split", "branchB"),
                        link("routeA1", "branchA", "taskA1"),
                        defaultLink("routeA2", "branchA", "taskA2"),
                        link("routeB1", "branchB", "taskB1"),
                        defaultLink("routeB2", "branchB", "taskB2")),
                "record-1", null, "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                null, null,
                List.of(new WorkflowManualRouteSelection("branchA", "routeA1", "choose A1"),
                        new WorkflowManualRouteSelection("branchB", "routeB2", "choose B2")));

        assertThat(draft.activation().traversedRouteKeys())
                .containsExactly("toSplit", "toBranchA", "toBranchB", "routeA1", "routeB2");
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("routeA1"))
                .first()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
                    assertThat(route.getSelectedReason()).isEqualTo("choose A1");
                });
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("routeB2"))
                .first()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
                    assertThat(route.getSelectedReason()).isEqualTo("choose B2");
                });
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("routeA2"))
                .first()
                .satisfies(route -> assertThat(route.getRouteReason())
                        .isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED));
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("routeB1"))
                .first()
                .satisfies(route -> assertThat(route.getRouteReason())
                        .isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED));
    }

    @Test
    void shouldRejectSubmitWhenInitialManualBranchHasNoSelectedRoute() {
        WorkflowNodeDefinition branch = manualBranch("branch", "START", false);

        assertThatThrownBy(() -> service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        branch,
                        node("leftTask", WorkflowNodeType.TASK),
                        node("rightTask", WorkflowNodeType.TASK)),
                List.of(link("toBranch", "start", "branch"),
                        link("leftRoute", "branch", "leftTask"),
                        defaultLink("rightRoute", "branch", "rightTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z")))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("manual branch requires selected route: branch");
    }

    @Test
    void shouldAllowSubmitManualBranchWhenStartSelectorMatchesStarter() {
        WorkflowNodeDefinition branch = manualBranch("branch", "START", false);

        WorkflowSubmitDraft draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        branch,
                        node("leftTask", WorkflowNodeType.TASK),
                        node("rightTask", WorkflowNodeType.TASK)),
                List.of(link("toBranch", "start", "branch"),
                        link("leftRoute", "branch", "leftTask"),
                        defaultLink("rightRoute", "branch", "rightTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", null);

        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("leftRoute"))
                .first()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
                    assertThat(route.getSelectedBy()).isEqualTo("user-1");
                });
    }

    @Test
    void shouldRejectSubmitManualBranchWhenRequiredReasonIsMissing() {
        WorkflowNodeDefinition branch = manualBranch("branch", "START", true);

        assertThatThrownBy(() -> service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        branch,
                        node("leftTask", WorkflowNodeType.TASK)),
                List.of(link("toBranch", "start", "branch"),
                        link("leftRoute", "branch", "leftTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", null))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("selection reason is required: branch");
    }

    @Test
    void shouldRejectSelectedRouteKeyWhenSubmitActivationCannotReachBranch() {
        assertThatThrownBy(() -> service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START),
                        node("approve", WorkflowNodeType.APPROVAL),
                        manualBranch("branch", "approve", false),
                        node("leftTask", WorkflowNodeType.TASK)),
                List.of(link("toApprove", "start", "approve"),
                        link("toBranch", "approve", "branch"),
                        link("leftRoute", "branch", "leftTask")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"),
                "leftRoute", null))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("selected route is not candidate outgoing route");
    }

    @Test
    void shouldBuildSubmitDraftForCompletedWorkflow() {
        WorkflowSubmitDraft draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START), node("end", WorkflowNodeType.END)),
                List.of(link("r1", "start", "end")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(draft.instance().getCurrentNodeKeys()).isEmpty();
        assertThat(draft.tasks()).isEmpty();
        assertThat(draft.activation().completed()).isTrue();
    }

    @Test
    void manualOverrideFreezesFalseConditionAndReasonWithoutReopeningConvergedPath() {
        var branch = manualBranch("branch", "start", true); branch.setConvergeNodeKey("merge");
        var merge = node("merge", WorkflowNodeType.CONVERGE); merge.setConvergeMode(WorkflowConvergeMode.ALL);
        var draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START), branch, merge, node("end", WorkflowNodeType.END)),
                List.of(link("enter", "start", "branch"), conditionLink("selected", "branch", "merge", "false"),
                        defaultLink("other", "branch", "merge"), link("exit", "merge", "end")),
                "record", "user-1", Instant.now(), "selected", "override advice");
        assertThat(draft.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("selected")).singleElement()
                .satisfies(route -> {
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.NORMAL_CONVERGED);
                    assertThat(route.getConditionMatched()).isFalse();
                    assertThat(route.getSelectedReason()).isEqualTo("override advice");
                });
    }

    @Test
    void automaticMatchedRouteStoresConditionFactAndReason() {
        var draft = service.build(definition(false), version(),
                List.of(node("start", WorkflowNodeType.START), node("branch", WorkflowNodeType.BRANCH),
                        node("task", WorkflowNodeType.TASK)),
                List.of(link("enter", "start", "branch"), conditionLink("matched", "branch", "task", "true")),
                "record", "user-1", Instant.now());
        assertThat(draft.routes()).filteredOn(route -> route.getRouteKey().equals("matched")).singleElement()
                .satisfies(route -> {
                    assertThat(route.getConditionMatched()).isTrue();
                    assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.CONDITION_MATCHED);
                });
    }

    private WorkflowDefinition definition(boolean approvalEnabled) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("definition-1");
        definition.setTenantId("tenant-1");
        definition.setModuleAlias("crm.customer");
        definition.setApprovalEnabled(approvalEnabled);
        return definition;
    }

    private WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId("version-1");
        version.setVersionNo(1);
        version.setSnapshotText("{}");
        version.setSemanticJson("{\"nodes\":[\"approve\"]}");
        version.setLayoutJson("{\"zoom\":1}");
        return version;
    }

    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeKey(key);
        node.setNodeType(type);
        node.setParticipantPolicyText("user:user-1");
        return node;
    }

    private WorkflowNodeDefinition manualBranch(String key, String selectorNodeKey, boolean requireReason) {
        WorkflowNodeDefinition node = node(key, WorkflowNodeType.BRANCH);
        node.setRouteMode(WorkflowRouteMode.MANUAL);
        node.setSelectorNodeKey(selectorNodeKey);
        node.setRequireManualSelectionReason(requireReason);
        return node;
    }

    private WorkflowLinkDefinition link(String key, String source, String target) {
        WorkflowLinkDefinition link = new WorkflowLinkDefinition();
        link.setRouteKey(key);
        link.setSourceNodeKey(source);
        link.setTargetNodeKey(target);
        return link;
    }

    private WorkflowLinkDefinition conditionLink(String key, String source, String target, String condition) {
        var link = link(key, source, target); link.setConditionExpression(condition); return link;
    }

    private WorkflowLinkDefinition defaultLink(String key, String source, String target) {
        WorkflowLinkDefinition link = link(key, source, target);
        link.setDefaultRoute(Boolean.TRUE);
        return link;
    }
}
