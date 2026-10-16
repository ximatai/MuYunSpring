package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRuntimeTaskFactoryTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowRuntimeTaskFactory factory = new WorkflowRuntimeTaskFactory(new WorkflowRuntimeEventFactory(),
                java.util.Optional.empty(),
                WorkflowTestSupport.participants());

    @Test
    void shouldCreateTasksForBlockingApprovalAndBusinessNodes() {
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approval = node(instance, "approve");
        WorkflowNodeInstance businessTask = node(instance, "businessTask");
        WorkflowActivationResult activation = new WorkflowActivationResult(
                List.of("approve", "businessTask"),
                List.of(),
                List.of("approve"),
                List.of("businessTask"),
                List.of(),
                List.of(),
                false
        );

        WorkflowRuntimeTaskDraft draft = factory.createBlockingTasks(instance,
                List.of(approval, businessTask), activation, "user-1",
                Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.tasks()).hasSize(2);
        assertThat(draft.tasks()).extracting(WorkflowTask::getTaskKind)
                .containsExactly(WorkflowTaskKind.APPROVAL, WorkflowTaskKind.BUSINESS);
        assertThat(draft.tasks()).extracting(WorkflowTask::getAssigneeId)
                .containsExactly("user-1", "user-1");
        assertThat(draft.tasks().get(0).getCheckStatus()).isEqualTo(WorkflowTaskCheckStatus.NO_CHECK);
        assertThat(draft.tasks().get(1).getCheckStatus()).isEqualTo(WorkflowTaskCheckStatus.NOT_CHECKED);
        assertThat(draft.events()).hasSize(2)
                .allSatisfy(event -> assertThat(event.getEventType()).isEqualTo(WorkflowEventType.TASK_CREATED));
    }

    @Test
    void shouldAssignApprovalTaskBySingleUserParticipantPolicy() {
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approval = node(instance, "approve");
        approval.setParticipantPolicyText("user:approver-2");
        WorkflowActivationResult activation = new WorkflowActivationResult(
                List.of("approve"),
                List.of(),
                List.of("approve"),
                List.of(),
                List.of(),
                List.of(),
                false
        );

        WorkflowRuntimeTaskDraft draft = factory.createBlockingTasks(instance,
                List.of(approval), activation, "operator-1",
                Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.tasks()).hasSize(1)
                .first()
                .satisfies(task -> {
                    assertThat(task.getAssigneeId()).isEqualTo("approver-2");
                    assertThat(task.getOwnerId()).isEqualTo("approver-2");
                    assertThat(task.getOriginalAssigneeId()).isEqualTo("approver-2");
                    assertThat(task.getAssignmentPolicyText()).isEqualTo("user:approver-2");
                    assertThat(task.getAssignmentSnapshotText()).contains("\"assigneeId\":\"approver-2\"");
                });
    }

    @Test
    void shouldAssignApprovalTaskByJsonUserParticipantPolicy() {
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approval = node(instance, "approve");
        approval.setParticipantPolicyText("""
                {"rules":[{"type":"USER","targetId":"approver-2"}]}
                """);

        WorkflowRuntimeTaskDraft draft = factory.createBlockingTasks(instance,
                List.of(approval), activation("approve"), "operator-1",
                Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.tasks()).hasSize(1)
                .first()
                .satisfies(task -> {
                    assertThat(task.getAssigneeId()).isEqualTo("approver-2");
                    assertThat(task.getOwnerId()).isEqualTo("approver-2");
                    assertThat(task.getOriginalAssigneeId()).isEqualTo("approver-2");
                    assertThat(task.getAssignmentPolicyText()).contains("\"targetId\":\"approver-2\"");
                    assertThat(task.getAssignmentSnapshotText()).contains("\"assigneeId\":\"approver-2\"");
                });
    }

    @Test
    void shouldFreezeDelegationWhenCreatingApprovalTask() {
        WorkflowDelegationService delegationService = new WorkflowDelegationService(new TestMemoryDao<>());
        WorkflowDelegation delegation = new WorkflowDelegation();
        delegation.setTitle("leave");
        delegation.setPrincipalUserId("approver-2");
        delegation.setDelegateUserId("delegate-1");
        delegation.setPrincipalCanProcess(true);
        delegation.setModuleScopeType(WorkflowDelegationScopeType.INCLUDE);
        delegation.setModuleAliases(java.util.Set.of("sales.contract"));
        delegation.setOrgScopeType(WorkflowDelegationScopeType.INCLUDE);
        delegation.setOrgIds(java.util.Set.of("org-1"));
        String delegationId = delegationService.insert(delegation);
        delegationService.enable(delegationId);
        WorkflowRuntimeTaskFactory delegatedFactory = new WorkflowRuntimeTaskFactory(new WorkflowRuntimeEventFactory(),
                java.util.Optional.of(delegationService),
                WorkflowTestSupport.participants());
        WorkflowInstance instance = instance();
        instance.setModuleAlias("sales.contract");
        instance.setAuthOrgId("org-1");
        WorkflowNodeInstance approval = node(instance, "approve");
        approval.setParticipantPolicyText("user:approver-2");

        WorkflowRuntimeTaskDraft draft = delegatedFactory.createBlockingTasks(instance,
                List.of(approval), activation("approve"), "operator-1",
                Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(draft.tasks()).hasSize(1)
                .first()
                .satisfies(task -> {
                    assertThat(task.getAssignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
                    assertThat(task.getOriginalAssigneeId()).isEqualTo("approver-2");
                    assertThat(task.getAssigneeId()).isEqualTo("delegate-1");
                    assertThat(task.getDelegatedFromUserId()).isEqualTo("approver-2");
                    assertThat(task.getDelegatedToUserId()).isEqualTo("delegate-1");
                    assertThat(task.getPrincipalCanProcess()).isTrue();
                    assertThat(task.getDelegationPolicyId()).isEqualTo(delegationId);
                    assertThat(task.getAssignmentSnapshotText()).contains("\"delegationPolicyId\"");
                });
    }

    @Test
    void shouldRejectAddSignApprovalTaskWithoutValidParticipantPolicy() {
        WorkflowInstance instance = instance();
        WorkflowNodeInstance missing = node(instance, "addMissing");
        missing.setAddedByAddSign(true);
        missing.setParticipantPolicyText(null);
        WorkflowNodeInstance invalid = node(instance, "addInvalid");
        invalid.setAddedByAddSign(true);
        invalid.setParticipantPolicyText("role:finance");
        WorkflowActivationResult missingActivation = activation("addMissing");
        WorkflowActivationResult invalidActivation = activation("addInvalid");

        assertThatThrownBy(() -> factory.createBlockingTasks(instance, List.of(missing), missingActivation,
                "operator-1", Instant.parse("2026-06-05T01:00:00Z")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("节点未解析到有效参与人");
        assertThatThrownBy(() -> factory.createBlockingTasks(instance, List.of(invalid), invalidActivation,
                "operator-1", Instant.parse("2026-06-05T01:00:00Z")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow participant policy");
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setModuleAlias("sales.contract");
        return instance;
    }

    private WorkflowNodeInstance node(WorkflowInstance instance, String key) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(key + "-node");
        node.setInstanceId(instance.getId());
        node.setNodeKey(key);
        node.setParticipantPolicyText("user:user-1");
        return node;
    }

    private WorkflowActivationResult activation(String nodeKey) {
        return new WorkflowActivationResult(
                List.of(nodeKey),
                List.of(),
                List.of(nodeKey),
                List.of(),
                List.of(),
                List.of(),
                false
        );
    }
}
