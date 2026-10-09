package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowInstanceStateServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowInstanceStateService service = new WorkflowInstanceStateService();

    @Test
    void shouldStartApprovalEnabledInstanceAsProcessing() {
        WorkflowInstance instance = service.startInstance(definition(true), version(),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), "{}");

        assertThat(instance.getApprovalEnabled()).isTrue();
        assertThat(instance.getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.PROCESSING);
        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(instance.getModuleAlias()).isEqualTo("crm.customer");
        assertThat(instance.getSemanticJson()).isEqualTo("{\"nodes\":[\"approve\"]}");
        assertThat(instance.getLayoutJson()).isEqualTo("{\"zoom\":1}");
    }

    @Test
    void shouldStartNonApprovalInstanceWithoutApprovalStatus() {
        WorkflowInstance instance = service.startInstance(definition(false), version(),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), "{}");

        assertThat(instance.getApprovalEnabled()).isFalse();
        assertThat(instance.getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.NONE);
    }

    @Test
    void shouldApplyApprovalCompletedMilestoneWithoutCompletingWorkflow() {
        WorkflowInstance instance = service.startInstance(definition(true), version(),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), "{}");
        WorkflowActivationResult activation = new WorkflowActivationResult(
                List.of("approvalDone", "postTask"),
                List.of("r1"),
                List.of(),
                List.of("postTask"),
                List.of(),
                List.of(WorkflowMilestoneType.APPROVAL_COMPLETED),
                false
        );

        service.applyActivation(instance, activation, Instant.parse("2026-06-05T02:00:00Z"));

        assertThat(instance.getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.APPROVED);
        assertThat(instance.getApprovalCompletedAt()).isEqualTo(Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("postTask");
    }

    @Test
    void shouldCompleteWorkflowWhenActivationHasNoBlockingNodes() {
        WorkflowInstance instance = service.startInstance(definition(false), version(),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"), "{}");
        WorkflowActivationResult activation = new WorkflowActivationResult(
                List.of("end"), List.of(), List.of(), List.of(), List.of(), List.of(), true
        );

        service.applyActivation(instance, activation, Instant.parse("2026-06-05T02:00:00Z"));

        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(instance.getCompletedAt()).isEqualTo(Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(instance.getCurrentNodeKeys()).isEmpty();
    }

    @Test
    void globalConfigurationCreatesInstanceInTheSubmittingTenant() {
        var shared = definition(false); shared.setTenantId(null);
        try (var context = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant-business")) {
            assertThat(service.startInstance(shared, version(), "record", "user", Instant.now(), "{}")
                    .getTenantId()).isEqualTo("tenant-business");
            assertThat(service.startInstance(definition(false), version(), "record", "user", Instant.now(), "{}")
                    .getTenantId()).isEqualTo("tenant-1");
        }
        assertThat(service.startInstance(shared, version(), "record", "user", Instant.now(), "{}")
                .getTenantId()).isNull();
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
        version.setSemanticJson("{\"nodes\":[\"approve\"]}");
        version.setLayoutJson("{\"zoom\":1}");
        return version;
    }
}
