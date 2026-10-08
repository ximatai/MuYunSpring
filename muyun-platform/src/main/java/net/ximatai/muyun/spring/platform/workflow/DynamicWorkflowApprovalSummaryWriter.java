package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.ApprovalAbility;
import net.ximatai.muyun.spring.ability.ApprovalState;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Single dispatcher for static and metadata-backed approval summaries. */
@Service
public class DynamicWorkflowApprovalSummaryWriter implements WorkflowApprovalSummaryWriter {
    private final DynamicRecordService records;
    private final Supplier<Stream<CrudAbility<?>>> abilities;

    public DynamicWorkflowApprovalSummaryWriter(DynamicRecordService records) {
        this(records, (Supplier<Stream<CrudAbility<?>>>) Stream::empty);
    }

    @Autowired
    public DynamicWorkflowApprovalSummaryWriter(DynamicRecordService records, ObjectProvider<CrudAbility<?>> abilities) {
        this(records, Objects.requireNonNull(abilities, "abilities")::orderedStream);
    }

    private DynamicWorkflowApprovalSummaryWriter(DynamicRecordService records, Supplier<Stream<CrudAbility<?>>> abilities) {
        this.records = Objects.requireNonNull(records, "records");
        this.abilities = abilities;
    }

    @Override
    public void writeSubmitted(WorkflowApprovalSummary summary) { writeSubmitted(summary, false); }

    @Override
    public void writeSubmittedIfPresent(WorkflowApprovalSummary summary) { writeSubmitted(summary, true); }

    private void writeSubmitted(WorkflowApprovalSummary summary, boolean allowMissingRecord) {
        Objects.requireNonNull(summary, "summary");
        write(summary.tenantId(), summary.moduleAlias(), summary.recordId(), new ApprovalState(
                summary.approvalInstanceId(), Objects.requireNonNull(summary.approvalStatus(), "approvalStatus").getCode(),
                summary.approvalSubmittedBy(), summary.approvalSubmittedAt(), summary.approvalCompletedAt()), allowMissingRecord);
    }

    @Override
    public void clearCurrent(String moduleAlias, String recordId) {
        clearCurrent(TenantContext.currentTenantId().orElse(null), moduleAlias, recordId);
    }

    @Override
    public void clearCurrent(String tenantId, String moduleAlias, String recordId) {
        write(tenantId, moduleAlias, recordId, ApprovalState.empty(), false);
    }

    @Override
    public void clearCurrentIfPresent(String tenantId, String moduleAlias, String recordId) {
        write(tenantId, moduleAlias, recordId, ApprovalState.empty(), true);
    }

    private void write(String tenantId, String moduleAlias, String recordId, ApprovalState state,
                       boolean allowMissingRecord) {
        // The tenant comes from the instance, including background/admin execution. Never enter system mode.
        try (var tenant = TenantContext.use(tenantId)) {
            ActionExecutionPolicy policy = ActionExecutionContextHolder.current()
                    .filter(context -> moduleAlias.equals(context.moduleAlias()))
                    .filter(context -> !context.hasRecordContext() || context.recordIds().contains(recordId))
                    .map(context -> context.actionPolicy()).orElse(PlatformAction.UPDATE.executionPolicy());
            var service = abilities.get().filter(item -> moduleAlias.equals(item.getModuleAlias())).findFirst();
            int updated;
            if (service.isPresent()) {
                if (!(service.get() instanceof ApprovalAbility<?> approval) || !approval.supportsApproval()) {
                    throw new PlatformException("static module does not support approval: " + moduleAlias);
                }
                if (allowMissingRecord) {
                    var record = service.get().selectActiveRaw(recordId);
                    if (record == null || !Objects.equals(tenantId, record.getTenantId())) return;
                }
                updated = approval.writeApprovalState(recordId, policy, state);
            } else {
                String entityAlias = records.mainEntityAlias(moduleAlias);
                if (allowMissingRecord && !records.existsActiveInCurrentTenant(moduleAlias, entityAlias, recordId)) return;
                updated = records.writeApprovalState(moduleAlias, entityAlias, recordId, policy, state);
            }
            if (updated != 1) throw new PlatformException("approval business record not found: " + moduleAlias + "." + recordId);
        }
    }
}
