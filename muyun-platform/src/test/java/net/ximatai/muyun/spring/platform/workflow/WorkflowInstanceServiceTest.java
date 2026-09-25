package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowInstanceServiceTest {
    private final WorkflowInstanceService service = new WorkflowInstanceService(new TestMemoryDao<>());

    @Test
    void standardWritesRejectBlankInstanceIdentityAndSnapshot() {
        WorkflowInstance invalid = instance("invalid", "sales.contract", " ", false);
        assertThatThrownBy(() -> service.insert(invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recordId");
        WorkflowInstance valid = instance("valid", "sales.contract", "record-1", false);
        service.insert(valid);
        valid.setSnapshotText(" ");
        assertThatThrownBy(() -> service.update(valid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("snapshotText");
    }

    @Test
    void shouldRejectSecondRunningApprovalInstanceForSameModuleRecord() {
        service.insert(instance("i1", "sales.contract", "record-1", true));

        assertThatThrownBy(() -> service.insert(instance("i2", "sales.contract", "record-1", true)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("approval workflow already running");
    }

    @Test
    void shouldAllowMultipleRunningNonApprovalWorkflowInstancesForSameModuleRecord() {
        service.insert(instance("i1", "sales.contract", "record-1", false));

        assertThatCode(() -> service.insert(instance("i2", "sales.contract", "record-1", false)))
                .doesNotThrowAnyException();
    }

    private WorkflowInstance instance(String id, String moduleAlias, String recordId, boolean approvalEnabled) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setDefinitionId("definition-" + id);
        instance.setWorkflowVersionId("version-" + id);
        instance.setVersionNo(1);
        instance.setModuleAlias(moduleAlias);
        instance.setRecordId(recordId);
        instance.setApprovalEnabled(approvalEnabled);
        instance.setApprovalStatus(approvalEnabled ? WorkflowApprovalStatus.PROCESSING : WorkflowApprovalStatus.NONE);
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setSnapshotText("{}");
        return instance;
    }
}
