package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class WorkflowSubmitFacade {
    private final ObjectProvider<WorkflowAutomaticApprovalService> automaticApprovals;
    private final WorkflowDefinitionSelector selector;
    private final WorkflowRuntimeSubmitService runtimeSubmitService;
    private final Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter;
    private final List<WorkflowModuleRecordGuard> recordGuards;

    @Autowired
    public WorkflowSubmitFacade(WorkflowDefinitionSelector selector,
                                WorkflowRuntimeSubmitService runtimeSubmitService,
                                Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter,
                                List<WorkflowModuleRecordGuard> recordGuards,
                                ObjectProvider<WorkflowAutomaticApprovalService> automaticApprovals) {
        this.automaticApprovals = java.util.Objects.requireNonNull(automaticApprovals, "automaticApprovals");
        this.selector = selector;
        this.runtimeSubmitService = runtimeSubmitService;
        this.approvalSummaryWriter = approvalSummaryWriter == null ? Optional.empty() : approvalSummaryWriter;
        this.recordGuards = recordGuards == null ? List.of() : List.copyOf(recordGuards);
    }

    @Transactional
    public WorkflowSubmitResult submit(WorkflowSubmitRequest request) {
        WorkflowSubmitRequest normalized = normalize(request);
        WorkflowMutationLock.record(normalized.moduleAlias(), normalized.recordId());
        recordGuards.forEach(guard -> guard.beforeSubmit(normalized));
        WorkflowDefinitionSelection selection = selector.select(normalized);
        WorkflowSubmitDraft draft = submitDraft(normalized, selection);
        boolean written = writeApprovalSummaryIfNeeded(normalized, draft);
        automaticApprovals.getObject().continueFor(draft.instance().getId(), normalized.operatorId(), normalized.operatedAt());
        WorkflowMutationFacts.recordChanged(draft.instance());
        return new WorkflowSubmitResult(draft, written);
    }

    public WorkflowSubmitPreview preview(WorkflowSubmitRequest request) {
        WorkflowSubmitRequest normalized = normalize(request);
        recordGuards.forEach(guard -> guard.beforeSubmit(normalized));
        WorkflowDefinitionSelection selection = selector.select(normalized);
        return new WorkflowSubmitPreview(selection, previewDraft(normalized, selection));
    }

    private WorkflowSubmitDraft submitDraft(WorkflowSubmitRequest request, WorkflowDefinitionSelection selection) {
        if (request.authOrgId() == null) {
            return runtimeSubmitService.submit(selection.definition(), selection.version(), selection.nodes(),
                    selection.links(), request.recordId(), request.operatorId(), request.operatedAt(),
                    request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
        }
        return runtimeSubmitService.submit(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), request.recordId(), request.authOrgId(), request.operatorId(), request.operatedAt(),
                request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
    }

    private WorkflowSubmitDraft previewDraft(WorkflowSubmitRequest request, WorkflowDefinitionSelection selection) {
        if (request.authOrgId() == null) {
            return runtimeSubmitService.preview(selection.definition(), selection.version(), selection.nodes(),
                    selection.links(), request.recordId(), request.operatorId(), request.operatedAt(),
                    request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
        }
        return runtimeSubmitService.preview(selection.definition(), selection.version(), selection.nodes(),
                selection.links(), request.recordId(), request.authOrgId(), request.operatorId(), request.operatedAt(),
                request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
    }

    private boolean writeApprovalSummaryIfNeeded(WorkflowSubmitRequest request, WorkflowSubmitDraft draft) {
        if (!draft.instance().getApprovalEnabled()) {
            return false;
        }
        WorkflowApprovalSummaryWriter writer = approvalSummaryWriter
                .orElseThrow(() -> new PlatformException("workflow approval summary writer is not configured"));
        WorkflowApprovalMutationScope.run(request.moduleAlias(), request.recordId(), "submitApproval",
                () -> writer.writeSubmitted(new WorkflowApprovalSummary(
                draft.instance().getTenantId(),
                request.moduleAlias(),
                request.recordId(),
                draft.instance().getId(),
                draft.instance().getApprovalStatus(),
                request.operatorId(),
                draft.instance().getStartedAt(),
                draft.instance().getApprovalCompletedAt()
        )));
        return true;
    }

    private WorkflowSubmitRequest normalize(WorkflowSubmitRequest request) {
        if (request == null) {
            throw new PlatformException("workflow submit request must not be null");
        }
        String moduleAlias = requireText(request.moduleAlias(), "moduleAlias");
        String recordId = requireText(request.recordId(), "recordId");
        String operatorId = request.operatorId();
        if (operatorId == null || operatorId.isBlank()) {
            operatorId = CurrentUserContext.currentUser()
                    .map(user -> user.userId())
                    .orElse("system");
        }
        Instant operatedAt = request.operatedAt() == null ? Instant.now() : request.operatedAt();
        String authOrgId = request.authOrgId() == null ? CurrentUserContext.currentUser()
                .map(user -> user.organizationId()).orElse(null) : request.authOrgId();
        if (authOrgId != null && authOrgId.isBlank()) {
            authOrgId = null;
        }
        String selectedRouteKey = textOrNull(request.selectedRouteKey());
        String selectedReason = textOrNull(request.selectedReason());
        return new WorkflowSubmitRequest(moduleAlias, recordId, request.approvalRequired(),
                request.definitionAlias(), authOrgId, operatorId, operatedAt, selectedRouteKey, selectedReason,
                request.manualRouteSelections());
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(name + " must not be blank");
        }
        return value;
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
