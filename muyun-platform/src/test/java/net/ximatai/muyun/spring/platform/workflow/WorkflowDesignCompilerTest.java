package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowDesignCompilerTest {
    private final WorkflowDesignCompiler compiler = new WorkflowDesignCompiler(new WorkflowConditionService(WorkflowTestSupport.facts()));

    @Test void validNestedBranchDomainsPublishWithBothConvergencesInsideTheirParents() {
        var design = nested();
        assertThat(compiler.validate(design.document(), true, false).nodes()).hasSize(10);
    }
    @Test void siblingPathsCannotMergeBeforeTheirPairedConvergence() {
        var design = new Design(); design.branch("branch", "join"); design.converge("join");
        design.approval("a"); design.approval("b"); design.approval("shared");
        design.edges("start>branch", "branch>a", "branch>b", "a>shared", "b>shared", "shared>join", "join>end");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("配对汇聚节点处合并");
    }
    @Test void nestedConvergenceCannotEscapeThroughParentConvergence() {
        var design = nested(); design.links.removeIf(link -> link.getSourceNodeKey().equals("a") || link.getSourceNodeKey().equals("b")
                || link.getSourceNodeKey().equals("innerJoin") || link.getSourceNodeKey().equals("after"));
        design.edges("a>outerJoin", "b>outerJoin", "outerJoin>innerJoin", "innerJoin>after", "after>end");
        design.links.removeIf(link -> link.getSourceNodeKey().equals("outerJoin") && link.getTargetNodeKey().equals("end"));
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("嵌套分支必须在父分支汇聚前结束");
    }
    @Test void branchesCannotShareAConvergence() {
        var design = nested(); design.node("inner").setConvergeNodeKey("outerJoin");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("汇聚");
    }
    @Test void businessTaskNeedsBothCompletionPolicyAndParticipantsBeforePublishing() {
        var design = new Design(); var task = design.add("business", WorkflowNodeType.TASK); task.setTaskDefinitionId("completion");
        design.edges("start>business", "business>end");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("参与人");
        task.setParticipantPolicyText("user:operator");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @Test void publicationRejectsCyclesUnreachableNodesAndBranchlessFanOut() {
        var cycle = new Design(); cycle.approval("approval"); cycle.edges("start>approval", "approval>start");
        assertThatThrownBy(() -> compiler.validate(cycle.document(), true, false)).hasMessageContaining("不支持循环");
        var unreachable = new Design(); unreachable.approval("orphan"); unreachable.edges("start>end", "orphan>end");
        assertThatThrownBy(() -> compiler.validate(unreachable.document(), true, false)).hasMessageContaining("不可达");
        var fanOut = new Design(); fanOut.approval("a"); fanOut.approval("b"); fanOut.edges("start>a", "start>b", "a>end", "b>end");
        assertThatThrownBy(() -> compiler.validate(fanOut.document(), true, false)).hasMessageContaining("并行出线");
    }
    @Test void emptyBranchConditionsAreUnconditionalAndDefaultExitCannotHaveACondition() {
        var design = nested();
        design.links.forEach(link -> link.setConditionExpression(null));
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
        var exit = design.links.stream().filter(link -> link.getSourceNodeKey().equals("outer")).findFirst().orElseThrow();
        exit.setDefaultRoute(true);
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
        exit.setConditionExpression("true");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("默认出口不能配置条件");
    }
    @Test void ordinaryLinksCannotPretendToEvaluateConditionsOrProvideDefaultExits() {
        var design = new Design(); design.edges("start>end");
        var link = design.links.getFirst(); link.setConditionExpression("false");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("分支直接出线");
        link.setConditionExpression(null); link.setDefaultRoute(true);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("分支直接出线");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = "  ")
    void routeKeysMustBePresentBeforeTheGraphCanBeSavedOrPublished(String key) {
        var design = new Design(); design.edges("start>end"); design.links.getFirst().setRouteKey(key);
        assertThatThrownBy(() -> compiler.validate(design.document(), false, false)).hasMessageContaining("路径编码不能为空");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("路径编码不能为空");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowNodeType.class, names = "MILESTONE", mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void otherNodeKindsCannotPretendToCompleteApproval(WorkflowNodeType type) {
        var design = new Design(); var fake = type == WorkflowNodeType.START ? design.node("start") : design.add("fake", type);
        fake.setMilestoneType(WorkflowMilestoneType.APPROVAL_COMPLETED);
        if (type == WorkflowNodeType.START) design.edges("start>end"); else design.edges("start>fake", "fake>end");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, true)).hasMessageContaining("里程碑类型只能");
    }
    @Test void optionalBranchActorsCannotOwnALaterManualBranch() {
        var design = manualAfterJoin();
        design.links.stream().filter(link -> "outer_a".equals(link.getRouteKey())).findFirst().orElseThrow()
                .setConditionExpression("{amount} > 100");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("所有可达路径");
        design.node("outer").setRouteMode(WorkflowRouteMode.MANUAL); design.node("outer").setSelectorNodeKey("start");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("所有可达路径");
    }
    @Test void allUnconditionalParallelPathsStillNeedAnIndependentDecisionActorAfterTheJoin() {
        var design = manualAfterJoin();
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
        design.node("outerJoin").setConvergeMode(WorkflowConvergeMode.RATIO); design.node("outerJoin").setConvergeRatio(100);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
        design.node("outerJoin").setConvergeRatio(50);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("所有可达路径");
        design.node("outerJoin").setConvergeMode(WorkflowConvergeMode.ANY);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("所有可达路径");
    }
    @Test void nestedAllJoinsCannotChooseOneEarlierParallelActorAsTheDecisionOwner() {
        var design = manualAfterJoin();
        design.links.removeIf(link -> "outer_a".equals(link.getRouteKey()) || "a_outerJoin".equals(link.getRouteKey()));
        design.branch("inner", "innerJoin"); design.converge("innerJoin"); design.approval("innerB");
        design.edges("outer>inner", "inner>a", "inner>innerB", "a>innerJoin", "innerB>innerJoin", "innerJoin>outerJoin");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
    }
    @Test void unreadNoticeNodesCannotProvideAnActualProcessorForManualSelection() {
        var design = manualAfterJoin(); design.node("a").setApprovalMode(WorkflowApprovalMode.NOTICE);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("选择来源");
    }
    @Test void parallelJoinSelectionCannotDependOnWhichDifferentActorFinishesLast() {
        var design = manualAfterJoin(); design.node("b").setParticipantPolicyText("user:another");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
        for (String policy : List.of("{\"type\":\"ROLE\",\"ids\":[\"reviewers\"]}",
                "{\"type\":\"FIELD\",\"fieldName\":\"reviewer\"}", "user:operator,another")) {
            design.node("b").setParticipantPolicyText(policy);
            assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
        }
        for (String key : List.of("a", "b")) design.node(key).setParticipantPolicyText("{\"type\":\"INITIATOR_SELF\"}");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("独立审批节点");
    }
    @Test void singleLinearSelectorRetainsDynamicParticipantSources() {
        var design = manualAfterJoin();
        design.links.removeIf(link -> "outerJoin_manual".equals(link.getRouteKey()));
        design.approval("selector"); design.node("selector").setParticipantPolicyText("{\"type\":\"ROLE\",\"ids\":[\"reviewers\"]}");
        design.node("manual").setSelectorNodeKey("selector"); design.edges("outerJoin>selector", "selector>manual");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
        design.node("selector").setParticipantPolicyText("{\"type\":\"FIELD\",\"fieldName\":\"reviewer\"}");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
        design.node("selector").setNodeType(WorkflowNodeType.TASK); design.node("selector").setTaskDefinitionId("completion");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowTaskGuideKind.class, names = {"OPEN_FORM", "EXECUTE_ACTION"})
    void frozenWritingGuidesCannotPredictConditionalAutoBeforeManualSelection(WorkflowTaskGuideKind kind) throws Exception {
        var design = manualFromTask(kind, "update", true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        assertThatCode(() -> compiler.validate(design.document(), false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false))
                .hasMessageContaining("请先落业务结果").hasMessageContaining("独立后继审批节点或任务节点")
                .hasMessageContaining("business -> manual");
    }
    @Test void customExecuteActionAlsoRequiresAPersistedBoundaryBeforeConditionalManualSelection() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.EXECUTE_ACTION, "recalculate", true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("请先落业务结果");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NOW() > '2020-01-01T00:00:00Z'", "TODAY() == '2030-01-01'", "YEAR(TODAY()) > 2020"})
    void timeDependentAutoConditionsAlsoNeedAPersistedBoundary(String condition) throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.link("auto_left").setConditionExpression(condition);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("请先落业务结果");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "true", "false", "1 > 0", "'NOW()' == 'NOW()'"})
    void unconditionalAndConstantAutoConditionsDoNotRequireAnotherAction(String condition) throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.link("auto_left").setConditionExpression(condition);
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowTaskGuideKind.class, names = {"OPEN_FORM", "EXECUTE_ACTION"})
    void writingTasksCanSelectDirectManualBranches(WorkflowTaskGuideKind kind) throws Exception {
        var design = manualFromTask(kind, "update", false);
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @Test void readOnlyGuidesCanReachManualSelectionThroughConditionalAuto() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.READ_INSTRUCTION, null, true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowNodeType.class, names = {"APPROVAL", "TASK"})
    void successorActionsCommitBusinessFactsBeforeLaterManualDecisions(WorkflowNodeType type) throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        design.links.removeIf(link -> "autoJoin_manual".equals(link.getRouteKey()));
        var selector = design.add("selector", type); selector.setParticipantPolicyText("user:operator");
        if (type == WorkflowNodeType.TASK) selector.setTaskDefinitionId("confirmation");
        design.node("manual").setSelectorNodeKey("selector");
        design.edges("autoJoin>selector", "selector>manual");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
    }
    @Test void nestedConditionalAutoCannotHideBehindAnOuterUnconditionalBranch() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.links.removeIf(link -> "auto_left".equals(link.getRouteKey()));
        design.branch("inner", "innerJoin"); design.converge("innerJoin");
        design.add("innerRight", WorkflowNodeType.MILESTONE);
        design.edges("auto>inner", "inner>left", "inner>innerRight", "innerRight>innerJoin", "innerJoin>autoJoin");
        design.link("left_autoJoin").setTargetNodeKey("innerJoin");
        design.link("inner_left").setConditionExpression("{amount} > 100");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("business -> manual");
    }
    @Test void nestedManualBranchInsideConditionalAutoAlsoNeedsAnIndependentAction() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.EXECUTE_ACTION, "update", true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        design.links.removeIf(link -> "left_autoJoin".equals(link.getRouteKey())
                || "autoJoin_manual".equals(link.getRouteKey()) || "manualJoin_end".equals(link.getRouteKey()));
        design.edges("left>manual", "manualJoin>autoJoin", "autoJoin>end");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("business -> manual");
    }
    @Test void noticeNodesDoNotIntroduceAPersistedActionBoundary() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.links.removeIf(link -> "business_auto".equals(link.getRouteKey()) || "autoJoin_manual".equals(link.getRouteKey()));
        for (String key : List.of("beforeNotice", "afterNotice")) {
            design.approval(key); design.node(key).setApprovalMode(WorkflowApprovalMode.NOTICE);
        }
        design.edges("business>beforeNotice", "beforeNotice>auto", "autoJoin>afterNotice", "afterNotice>manual");
        assertThatCode(() -> compiler.validate(design.document(), true, false)).doesNotThrowAnyException();
        design.link("auto_left").setConditionExpression("{amount} > 100");
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("business -> manual");
    }
    @Test void aConditionalDefaultRouteStillNeedsAPersistedActionBeforeManualSelection() throws Exception {
        var design = manualFromTask(WorkflowTaskGuideKind.OPEN_FORM, "update", true);
        design.link("auto_left").setConditionExpression("{amount} > 100");
        design.link("auto_right").setDefaultRoute(true); design.link("auto_right").setConditionExpression(null);
        assertThatThrownBy(() -> compiler.validate(design.document(), true, false)).hasMessageContaining("business -> manual");
    }
    private Design manualFromTask(WorkflowTaskGuideKind kind, String action, boolean automatic) throws Exception {
        var design = new Design();
        var task = design.add("business", WorkflowNodeType.TASK); task.setParticipantPolicyText("user:operator");
        task.setTaskDefinitionId("completion");
        var definition = new WorkflowTaskDefinition(); definition.setId("completion"); definition.setModuleAlias("sales.contract");
        var guide = new WorkflowTaskGuide(); guide.setGuideKey("guide"); guide.setGuideKind(kind); guide.setTargetActionCode(action);
        task.setNodeConfigText(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(
                java.util.Map.of("task", new WorkflowBusinessTaskSpec(definition, List.of(), List.of(guide)))));
        design.branch("manual", "manualJoin"); design.node("manual").setRouteMode(WorkflowRouteMode.MANUAL);
        design.node("manual").setSelectorNodeKey("business"); design.converge("manualJoin");
        design.add("x", WorkflowNodeType.MILESTONE); design.add("y", WorkflowNodeType.MILESTONE);
        design.edges("start>business", "manual>x", "manual>y", "x>manualJoin", "y>manualJoin", "manualJoin>end");
        if (automatic) {
            design.branch("auto", "autoJoin"); design.converge("autoJoin");
            design.add("left", WorkflowNodeType.MILESTONE); design.add("right", WorkflowNodeType.MILESTONE);
            design.edges("business>auto", "auto>left", "auto>right", "left>autoJoin", "right>autoJoin", "autoJoin>manual");
        } else design.edges("business>manual");
        return design;
    }
    private Design manualAfterJoin() {
        var design = new Design(); design.branch("outer", "outerJoin"); design.converge("outerJoin");
        design.approval("a"); design.approval("b");
        design.branch("manual", "manualJoin"); design.node("manual").setRouteMode(WorkflowRouteMode.MANUAL);
        design.node("manual").setSelectorNodeKey("a"); design.converge("manualJoin");
        design.approval("x"); design.approval("y");
        design.edges("start>outer", "outer>a", "outer>b", "a>outerJoin", "b>outerJoin", "outerJoin>manual",
                "manual>x", "manual>y", "x>manualJoin", "y>manualJoin", "manualJoin>end");
        return design;
    }
    private Design nested() {
        var design = new Design(); design.branch("outer", "outerJoin"); design.branch("inner", "innerJoin");
        design.converge("outerJoin"); design.converge("innerJoin");
        for (var key : List.of("a", "b", "after", "right")) design.approval(key);
        design.edges("start>outer", "outer>inner", "outer>right", "inner>a", "inner>b", "a>innerJoin", "b>innerJoin",
                "innerJoin>after", "after>outerJoin", "right>outerJoin", "outerJoin>end"); return design;
    }
    private static class Design {
        final List<WorkflowNodeDefinition> nodes = new ArrayList<>(); final List<WorkflowLinkDefinition> links = new ArrayList<>();
        Design() { add("start", WorkflowNodeType.START); add("end", WorkflowNodeType.END); }
        WorkflowNodeDefinition add(String key, WorkflowNodeType type) { var node = new WorkflowNodeDefinition(); node.setNodeKey(key); node.setNodeType(type); nodes.add(node); return node; }
        WorkflowNodeDefinition node(String key) { return nodes.stream().filter(node -> key.equals(node.getNodeKey())).findFirst().orElseThrow(); }
        WorkflowLinkDefinition link(String key) { return links.stream().filter(link -> key.equals(link.getRouteKey())).findFirst().orElseThrow(); }
        void approval(String key) { add(key, WorkflowNodeType.APPROVAL).setParticipantPolicyText("user:operator"); }
        void converge(String key) { add(key, WorkflowNodeType.CONVERGE).setConvergeMode(WorkflowConvergeMode.ALL); }
        void branch(String key, String join) { var node = add(key, WorkflowNodeType.BRANCH); node.setConvergeNodeKey(join); node.setRouteMode(WorkflowRouteMode.AUTO); }
        void edges(String... pairs) { for (var pair : pairs) { var split = pair.split(">"); var link = new WorkflowLinkDefinition();
            link.setRouteKey(split[0] + "_" + split[1]); link.setSourceNodeKey(split[0]); link.setTargetNodeKey(split[1]); if (node(split[0]).getNodeType() == WorkflowNodeType.BRANCH) link.setConditionExpression("true"); links.add(link); } }
        WorkflowDesignDocument document() { return new WorkflowDesignDocument(nodes, links, null); }
    }
}
