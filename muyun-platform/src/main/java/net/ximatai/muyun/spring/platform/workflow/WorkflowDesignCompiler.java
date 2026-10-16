package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaNode;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** Validates the authored semantic graph before it becomes immutable executable configuration. */
@Service
public class WorkflowDesignCompiler {
    private final WorkflowConditionService conditions;
    private final FormulaEngine formulas = new FormulaEngine();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public WorkflowDesignCompiler(WorkflowConditionService conditions) { this.conditions = conditions; }

    public void validateSelectionExpression(String expression) { conditions.validate(expression); }

    public WorkflowRuntimeGraph validate(WorkflowDesignDocument document, boolean publishing, boolean approvalEnabled) {
        if (document == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.provide-a-workflow-design",
                "workflow design is required"));
        var graph = WorkflowRuntimeGraph.of(document.nodes(), document.links());
        document.links().forEach(link -> {
            if (link.getRouteKey() == null || link.getRouteKey().isBlank())
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.enter-a-route-key",
                        "路径编码不能为空"));
            conditions.validate(link.getConditionExpression());
        });
        if (!publishing) return graph;
        for (var link : document.links()) {
            boolean branchExit = graph.requireNode(link.getSourceNodeKey()).getNodeType() == WorkflowNodeType.BRANCH;
            boolean hasCondition = link.getConditionExpression() != null && !link.getConditionExpression().isBlank();
            if (!branchExit && (hasCondition || Boolean.TRUE.equals(link.getDefaultRoute())))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.conditions-and-default-routes-are-only-allowed-directly-after-a-branch",
                        "条件和默认出口只能配置在分支直接出线上: {value1}"), Map.of("value1", String.valueOf(link.getRouteKey())));
            if (Boolean.TRUE.equals(link.getDefaultRoute()) && hasCondition)
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.default-routes-cannot-have-conditions",
                        "默认出口不能配置条件: {value1}"), Map.of("value1", String.valueOf(link.getRouteKey())));
        }
        if (graph.startNodes().size() != 1) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.the-workflow-must-have-exactly-one-start-node",
                "流程必须有且只有一个开始节点"));
        if (document.nodes().stream().noneMatch(node -> node.getNodeType() == WorkflowNodeType.END))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.add-an-end-node-to-the-workflow",
                    "流程必须包含结束节点"));
        Set<String> visited = new HashSet<>();
        visit(graph, graph.startNodes().getFirst().getNodeKey(), visited, new HashSet<>());
        if (visited.size() != graph.nodes().size()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.the-workflow-contains-unreachable-nodes",
                "流程存在不可达节点"));
        for (var node : document.nodes()) {
            if (node.getNodeType() == null) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.select-a-node-type",
                    "节点类型不能为空: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            if (node.getMilestoneType() != null && node.getNodeType() != WorkflowNodeType.MILESTONE)
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.milestone-types-are-only-allowed-on-milestone-nodes",
                        "里程碑类型只能配置在里程碑节点上: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            var outgoing = graph.outgoing(node.getNodeKey());
            if (node.getNodeType() == WorkflowNodeType.END && !outgoing.isEmpty())
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.end-nodes-cannot-have-outgoing-routes",
                        "结束节点不能有出线: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            if (node.getNodeType() != WorkflowNodeType.END && outgoing.isEmpty())
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.connect-the-node-to-a-following-node",
                        "节点必须连接后续节点: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            if (node.getNodeType() != WorkflowNodeType.BRANCH && outgoing.size() > 1)
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.configure-parallel-routes-through-a-branch-node",
                        "并行出线必须通过分支节点配置: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            if (node.getNodeType() == WorkflowNodeType.APPROVAL || node.getNodeType() == WorkflowNodeType.TASK) {
                if (node.getParticipantPolicyText() == null || node.getParticipantPolicyText().isBlank())
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-design-compiler.select-node-participants",
                            "请选择节点参与人: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
                WorkflowParticipantPolicyCodec.validate(node.getParticipantPolicyText(), node.getNodeKey());
                if (node.getApprovalMode() == WorkflowApprovalMode.RATIO) ratio(node.getApprovalRatio(), node.getNodeKey());
            }
            if (node.getNodeType() == WorkflowNodeType.TASK && node.getTaskDefinitionId() == null
                    && (node.getNodeConfigText() == null || node.getNodeConfigText().isBlank()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.configure-a-business-task-completion-policy",
                        "请配置业务任务完成策略: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
            if (node.getNodeType() == WorkflowNodeType.CONVERGE
                    && node.getConvergeMode() == WorkflowConvergeMode.RATIO) ratio(node.getConvergeRatio(), node.getNodeKey());
            if (node.getNodeType() == WorkflowNodeType.BRANCH) {
                if (outgoing.size() < 2) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.a-branch-needs-at-least-two-outgoing-routes",
                        "分支必须至少有两条出线: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
                if (outgoing.stream().filter(link -> Boolean.TRUE.equals(link.getDefaultRoute())).count() > 1)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-design-compiler.a-branch-can-have-only-one-default-route",
                            "分支只能设置一条默认出线: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
                var converge = graph.nodes().get(node.getConvergeNodeKey());
                if (converge == null || converge.getNodeType() != WorkflowNodeType.CONVERGE)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-design-compiler.connect-the-branch-to-a-merge-node",
                            "分支必须绑定汇聚节点: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
                for (var link : outgoing) requireConverges(graph, link.getTargetNodeKey(), converge.getNodeKey(), new HashSet<>());
            }
        }
        validateBranchDomains(graph, document.nodes());
        for (var node : document.nodes()) {
            if (node.getNodeType() != WorkflowNodeType.BRANCH || node.getRouteMode() != WorkflowRouteMode.MANUAL) continue;
            validateManualSelectionTopology(graph, node);
        }
        validateWritableTaskSelections(graph, document.nodes());
        if (approvalEnabled) {
            if (document.nodes().stream().noneMatch(node -> node.getMilestoneType() == WorkflowMilestoneType.APPROVAL_COMPLETED))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.an-approval-workflow-must-include-an-approval-completion-milestone",
                        "审批治理流程必须包含审批完成里程碑"));
            requireApprovalMilestone(graph, graph.startNodes().getFirst().getNodeKey(), false, new HashSet<>());
        }
        return graph;
    }

    /** Shared frozen/runtime topology contract: a decision belongs to the action completing its selector. */
    static void validateManualSelectionTopology(WorkflowRuntimeGraph graph, WorkflowNodeDefinition branch) {
        String responsibility = branch.getNodeKey() + "（选择来源 " + branch.getSelectorNodeKey() + "）";
        var selector = branch.getSelectorNodeKey() == null ? null : graph.nodes().get(branch.getSelectorNodeKey());
        if (selector == null || (selector.getNodeType() != WorkflowNodeType.START
                && selector.getNodeType() != WorkflowNodeType.APPROVAL && selector.getNodeType() != WorkflowNodeType.TASK))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.select-a-start-approval-or-task-node-as-the-manual-branch-selector",
                    "手工分支必须指定开始、审批或任务节点作为选择人来源: {value1}"), Map.of("value1", String.valueOf(responsibility)));
        if (selector.getNodeType() == WorkflowNodeType.APPROVAL && selector.getApprovalMode() == WorkflowApprovalMode.NOTICE
                || !canReachWithoutBlock(graph, selector.getNodeKey(), branch.getNodeKey(), new HashSet<>()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.the-selector-must-be-on-a-reachable-path-in-the-current-round",
                    "手工分支选择来源必须在本轮可达路径上: {value1}"), Map.of("value1", String.valueOf(responsibility)));
        if (canActivateWithoutSelector(graph, branch.getNodeKey(), branch.getSelectorNodeKey(), new HashMap<>()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.the-selector-must-have-processed-its-task-on-every-path-reaching-the",
                    "手工分支的所有可达路径必须保证选择人已办理: {value1}"), Map.of("value1", String.valueOf(responsibility)));
        validateSelectionTriggerActors(graph, branch);
    }

    private void validateBranchDomains(WorkflowRuntimeGraph graph, List<WorkflowNodeDefinition> nodes) {
        var paired = new HashSet<String>();
        for (var branch : nodes) {
            if (branch.getNodeType() != WorkflowNodeType.BRANCH) continue;
            if (!paired.add(branch.getConvergeNodeKey())) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.a-merge-node-can-be-paired-with-only-one-branch",
                    "汇聚节点只能配对一个分支: {value1}"), Map.of("value1", String.valueOf(branch.getConvergeNodeKey())));
            var domain = new HashSet<String>();
            for (var route : graph.outgoing(branch.getNodeKey())) {
                var path = new HashSet<String>();
                collectBeforeConverge(graph, route.getTargetNodeKey(), branch.getConvergeNodeKey(), path);
                if (path.stream().anyMatch(domain::contains)) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.branch-routes-must-join-at-their-paired-merge-node",
                        "分支路径必须在配对汇聚节点处合并: {value1}"), Map.of("value1", String.valueOf(branch.getNodeKey())));
                domain.addAll(path);
            }
            for (var key : domain) {
                var nested = graph.requireNode(key);
                if (nested.getNodeType() == WorkflowNodeType.BRANCH && !domain.contains(nested.getConvergeNodeKey()))
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-design-compiler.nested-branches-must-merge-before-their-parent-branch",
                            "嵌套分支必须在父分支汇聚前结束: {value1}"), Map.of("value1", String.valueOf(key)));
            }
            if (graph.incoming(branch.getConvergeNodeKey()).stream().anyMatch(route -> !domain.contains(route.getSourceNodeKey())
                    && !branch.getNodeKey().equals(route.getSourceNodeKey())))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-design-compiler.a-merge-cannot-accept-routes-outside-its-paired-branch",
                        "汇聚节点不能接收配对分支之外的路径: {value1}"), Map.of("value1", String.valueOf(branch.getConvergeNodeKey())));
        }
    }

    private static void collectBeforeConverge(WorkflowRuntimeGraph graph, String key, String converge, Set<String> visited) {
        if (key.equals(converge) || !visited.add(key)) return;
        graph.outgoing(key).forEach(route -> collectBeforeConverge(graph, route.getTargetNodeKey(), converge, visited));
    }

    private void visit(WorkflowRuntimeGraph graph, String key, Set<String> visited, Set<String> path) {
        if (!path.add(key)) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.workflow-cycles-are-not-supported-use-resubmission-or-rollback",
                "流程暂不支持循环，请使用驳回重提或回退: {value1}"), Map.of("value1", String.valueOf(key)));
        if (visited.add(key)) graph.outgoing(key).forEach(link -> visit(graph, link.getTargetNodeKey(), visited, new HashSet<>(path)));
    }

    private void requireConverges(WorkflowRuntimeGraph graph, String key, String converge, Set<String> visited) {
        if (key.equals(converge) || !visited.add(key)) return;
        if (graph.outgoing(key).isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.the-branch-route-must-reach-its-paired-merge-node",
                "分支路径必须到达配对汇聚节点: {value1}"), Map.of("value1", String.valueOf(key)));
        graph.outgoing(key).forEach(link -> requireConverges(graph, link.getTargetNodeKey(), converge, visited));
    }

    private static boolean canReachWithoutBlock(WorkflowRuntimeGraph graph, String key, String target, Set<String> visited) {
        if (key.equals(target)) return true;
        if (!visited.add(key)) return false;
        return graph.outgoing(key).stream().anyMatch(link -> {
            var next = graph.requireNode(link.getTargetNodeKey());
            return next.getNodeKey().equals(target) || (!blocksProgression(next)
                    && canReachWithoutBlock(graph, next.getNodeKey(), target, visited));
        });
    }

    /** Business writes may derive fields or run hooks. A draft cannot predict which conditional AUTO
     * exits will activate, so a later manual decision needs its own persisted-fact action boundary. */
    private void validateWritableTaskSelections(WorkflowRuntimeGraph graph, List<WorkflowNodeDefinition> nodes) {
        var conditionalBranches = new HashSet<String>();
        for (var node : nodes) {
            if (node.getNodeType() == WorkflowNodeType.BRANCH && node.getRouteMode() != WorkflowRouteMode.MANUAL
                    && graph.outgoing(node.getNodeKey()).stream().anyMatch(route -> dependsOnFactsOrTime(route.getConditionExpression())))
                conditionalBranches.add(node.getNodeKey());
        }
        for (var node : nodes) {
            if (node.getNodeType() != WorkflowNodeType.TASK || !hasWritingGuide(node)) continue;
            var visited = new HashSet<String>();
            for (var route : graph.outgoing(node.getNodeKey()))
                requireStableManualSelection(graph, route.getTargetNodeKey(), node.getNodeKey(), false, conditionalBranches, visited);
        }
    }

    private boolean hasWritingGuide(WorkflowNodeDefinition node) {
        if (node.getNodeConfigText() == null || node.getNodeConfigText().isBlank()) return false;
        try {
            var frozen = mapper.readTree(node.getNodeConfigText()).get("task");
            if (frozen == null || frozen.isNull()) return false;
            var spec = mapper.treeToValue(frozen, WorkflowBusinessTaskSpec.class);
            return spec.guides().stream().anyMatch(guide -> guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM
                    || guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION);
        } catch (Exception failure) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.invalid-frozen-business-task-specification",
                    "业务任务冻结规格不合法: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())), failure);
        }
    }

    private void requireStableManualSelection(WorkflowRuntimeGraph graph, String key, String task, boolean conditional,
                                             Set<String> conditionalBranches, Set<String> visited) {
        var node = graph.requireNode(key);
        if (blocksProgression(node)) return;
        conditional |= conditionalBranches.contains(key);
        if (!visited.add(key + ":" + conditional)) return;
        if (conditional && node.getNodeType() == WorkflowNodeType.BRANCH && node.getRouteMode() == WorkflowRouteMode.MANUAL)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.an-automatic-branch-after-a-business-write-cannot-continue-with-manual-selection",
                    "写入业务任务后的条件自动分支不能在同次办理中继续手工选路，请先落业务结果，再增加独立后继审批节点或任务节点作为选择来源: {value1} -> {value2}"), Map.of("value1", String.valueOf(task), "value2", String.valueOf(key)));
        boolean nextConditional = conditional;
        graph.outgoing(key).forEach(route -> requireStableManualSelection(graph, route.getTargetNodeKey(), task,
                nextConditional, conditionalBranches, visited));
    }

    private static boolean blocksProgression(WorkflowNodeDefinition node) {
        return node.getNodeType() == WorkflowNodeType.TASK || node.getNodeType() == WorkflowNodeType.APPROVAL
                && node.getApprovalMode() != WorkflowApprovalMode.NOTICE;
    }

    private boolean dependsOnFactsOrTime(String expression) {
        if (expression == null || expression.isBlank()) return false;
        if (!formulas.referencedFields(expression).isEmpty()) return true;
        try {
            // The issued scalar AST distinguishes time calls from identical text inside literals.
            // Expressions outside this known subset cannot be proven constant and remain conservative.
            return usesTime(formulas.compileFormValidationProgram(expression).root());
        } catch (FormulaEvaluationException unsupported) {
            return true;
        }
    }

    private boolean usesTime(FormulaNode node) {
        return node.kind() == FormulaNode.Kind.FUNCTION && Set.of("NOW", "TODAY").contains(node.operator())
                || node.arguments().stream().anyMatch(this::usesTime);
    }

    /** ALL waits for unconditional AUTO exits, even though no individual graph path dominates the join.
     * Conditional/default exits may be absent; ANY and partial ratios cannot guarantee a particular actor. */
    private static boolean canActivateWithoutSelector(WorkflowRuntimeGraph graph, String key, String selector,
                                               java.util.Map<String, Boolean> memo) {
        if (key.equals(selector)) return false;
        if (memo.containsKey(key)) return memo.get(key);
        var node = graph.requireNode(key);
        boolean possible = node.getNodeType() == WorkflowNodeType.START || graph.incoming(key).stream()
                .anyMatch(route -> canActivateWithoutSelector(graph, route.getSourceNodeKey(), selector, memo));
        if (possible && node.getNodeType() == WorkflowNodeType.CONVERGE
                && (node.getConvergeMode() == null || node.getConvergeMode() == WorkflowConvergeMode.ALL
                    || node.getConvergeMode() == WorkflowConvergeMode.RATIO && Integer.valueOf(100).equals(node.getConvergeRatio()))) {
            var branch = graph.nodes().values().stream().filter(item -> item.getNodeType() == WorkflowNodeType.BRANCH
                    && key.equals(item.getConvergeNodeKey())).findFirst().orElse(null);
            if (branch != null && branch.getRouteMode() != WorkflowRouteMode.MANUAL) {
                for (var exit : graph.outgoing(branch.getNodeKey())) {
                    if (Boolean.TRUE.equals(exit.getDefaultRoute()) || !unconditional(exit.getConditionExpression())) continue;
                    var path = new HashSet<String>();
                    collectBeforeConverge(graph, exit.getTargetNodeKey(), key, path);
                    boolean arrives = exit.getTargetNodeKey().equals(key)
                            ? canActivateWithoutSelector(graph, branch.getNodeKey(), selector, memo)
                            : graph.incoming(key).stream().filter(route -> path.contains(route.getSourceNodeKey()))
                                .anyMatch(route -> canActivateWithoutSelector(graph, route.getSourceNodeKey(), selector, memo));
                    if (!arrives) { possible = false; break; }
                }
            }
        }
        memo.put(key, possible);
        return possible;
    }

    private static boolean unconditional(String expression) {
        return expression == null || expression.isBlank() || "true".equals(expression.trim());
    }

    /** Selection and the action that reaches it are atomic. A join cannot require a different actor's decision. */
    private static void validateSelectionTriggerActors(WorkflowRuntimeGraph graph, WorkflowNodeDefinition branch) {
        var selector = graph.requireNode(branch.getSelectorNodeKey());
        var triggers = new HashSet<String>();
        collectTriggerActors(graph, branch.getNodeKey(), triggers, new HashSet<>());
        for (String key : triggers) {
            if (key.equals(selector.getNodeKey()) || reaches(graph, key, selector.getNodeKey(), new HashSet<>())) continue;
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.the-manual-branch-must-be-selected-during-its-selector-node-s-current",
                    "手工分支必须由选择来源节点的本次办理触发，请在汇聚后增加独立审批节点或任务节点作为选择来源: {value1}（选择来源 {value2}）"), Map.of("value1", String.valueOf(branch.getNodeKey()), "value2", String.valueOf(branch.getSelectorNodeKey())));
        }
    }

    private static void collectTriggerActors(WorkflowRuntimeGraph graph, String key, Set<String> triggers, Set<String> visited) {
        if (!visited.add(key)) return;
        for (var route : graph.incoming(key)) {
            var source = graph.requireNode(route.getSourceNodeKey());
            if (source.getNodeType() == WorkflowNodeType.START || source.getNodeType() == WorkflowNodeType.TASK
                    || source.getNodeType() == WorkflowNodeType.APPROVAL && source.getApprovalMode() != WorkflowApprovalMode.NOTICE)
                triggers.add(source.getNodeKey());
            else collectTriggerActors(graph, source.getNodeKey(), triggers, visited);
        }
    }

    private static boolean reaches(WorkflowRuntimeGraph graph, String key, String target, Set<String> visited) {
        if (key.equals(target)) return true;
        return visited.add(key) && graph.outgoing(key).stream()
                .anyMatch(route -> reaches(graph, route.getTargetNodeKey(), target, visited));
    }


    private void requireApprovalMilestone(WorkflowRuntimeGraph graph, String key, boolean approved, Set<String> visited) {
        var node = graph.requireNode(key);
        approved |= node.getNodeType() == WorkflowNodeType.MILESTONE
                && node.getMilestoneType() == WorkflowMilestoneType.APPROVAL_COMPLETED;
        if (!visited.add(key + approved)) return;
        if (node.getNodeType() == WorkflowNodeType.END && !approved)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-design-compiler.every-approval-workflow-path-must-pass-through-an-approval-completion-milestone",
                    "审批流程每条结束路径都必须经过审批完成里程碑"));
        boolean nextApproved = approved;
        graph.outgoing(key).forEach(link -> requireApprovalMilestone(graph, link.getTargetNodeKey(), nextApproved, visited));
    }

    private void ratio(Integer ratio, String key) {
        if (ratio == null || ratio < 1 || ratio > 100) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-design-compiler.the-ratio-must-be-between-1-and-100",
                "比例必须在 1 到 100 之间: {value1}"), Map.of("value1", String.valueOf(key)));
    }

    public String serialize(WorkflowDesignDocument document) {
        try { return mapper.writeValueAsString(document); }
        catch (Exception failure) { throw new PlatformException("cannot serialize workflow design", failure); }
    }

    public WorkflowDesignDocument deserialize(String text) {
        try { return mapper.readValue(text, WorkflowDesignDocument.class); }
        catch (Exception failure) { throw new PlatformException("invalid workflow design snapshot", failure); }
    }
}
