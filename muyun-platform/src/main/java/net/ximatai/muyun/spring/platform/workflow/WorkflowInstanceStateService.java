package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkflowInstanceStateService {
    public WorkflowInstance startInstance(WorkflowDefinition definition, WorkflowVersion version,
                                          String recordId, String startedBy, Instant startedAt,
                                          String snapshotText) {
        return startInstance(definition, version, recordId, null, startedBy, startedAt, snapshotText);
    }

    public WorkflowInstance startInstance(WorkflowDefinition definition, WorkflowVersion version,
                                          String recordId, String authOrgId, String startedBy, Instant startedAt,
                                          String snapshotText) {
        if (definition == null || version == null) {
            throw new PlatformException("workflow definition and version must not be null");
        }
        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinitionId(requireText(definition.getId(), "workflow definition id must not be blank"));
        instance.setWorkflowVersionId(requireText(version.getId(), "workflow version id must not be blank"));
        if (version.getVersionNo() == null || version.getVersionNo() <= 0) {
            throw new PlatformException("workflow version number must be positive");
        }
        instance.setVersionNo(version.getVersionNo());
        instance.setTenantId(definition.getTenantId() == null
                ? net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null)
                : definition.getTenantId());
        instance.setModuleAlias(PlatformNameRules.requireModuleAlias(definition.getModuleAlias()));
        instance.setRecordId(requireText(recordId, "workflow record id must not be blank"));
        instance.setAuthOrgId(authOrgId == null || authOrgId.isBlank() ? null : authOrgId.trim());
        instance.setApprovalEnabled(Boolean.TRUE.equals(definition.getApprovalEnabled()));
        instance.setApprovalStatus(Boolean.TRUE.equals(definition.getApprovalEnabled())
                ? WorkflowApprovalStatus.PROCESSING : WorkflowApprovalStatus.NONE);
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setStartedBy(startedBy);
        instance.setStartedAt(startedAt == null ? Instant.now() : startedAt);
        instance.setSnapshotText(requireText(snapshotText, "workflow snapshot must not be blank"));
        instance.setSemanticJson(blankToNull(version.getSemanticJson()));
        instance.setLayoutJson(blankToNull(version.getLayoutJson()));
        return instance;
    }

    public void applyActivation(WorkflowInstance instance, WorkflowActivationResult activation, Instant operatedAt) {
        if (instance == null || activation == null) {
            throw new PlatformException("workflow instance and activation result must not be null");
        }
        Instant now = operatedAt == null ? Instant.now() : operatedAt;
        if (Boolean.TRUE.equals(instance.getApprovalEnabled()) && activation.approvalCompleted()) {
            instance.setApprovalStatus(WorkflowApprovalStatus.APPROVED);
            if (instance.getApprovalCompletedAt() == null) {
                instance.setApprovalCompletedAt(now);
            }
        }
        List<String> currentNodes = new ArrayList<>();
        currentNodes.addAll(activation.blockingApprovalNodeKeys());
        currentNodes.addAll(activation.blockingTaskNodeKeys());
        currentNodes.addAll(activation.waitingConvergeNodeKeys());
        instance.setCurrentNodeKeys(String.join(",", currentNodes));
        if (activation.completed() && currentNodes.isEmpty()) {
            instance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
            if (instance.getCompletedAt() == null) {
                instance.setCompletedAt(now);
            }
        } else {
            instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
