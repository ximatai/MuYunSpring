package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentFacts;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.FieldTarget;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Current and frozen opinions retain their owning workflow's ordinary record-view boundary. */
@Service
public final class WorkflowOpinionAccess {
    private final WorkflowInstanceDao instances;
    private final WorkflowHistoryInstanceDao histories;
    private final WorkflowEventDao events;
    private final WorkflowArchiveService archives;
    private final WorkflowActionPolicyService policies;
    private final BusinessContentFacts content;
    public WorkflowOpinionAccess(WorkflowInstanceDao instances, WorkflowHistoryInstanceDao histories,
            WorkflowEventDao events, WorkflowArchiveService archives, WorkflowActionPolicyService policies,
            BusinessContentFacts content) {
        this.instances = instances; this.histories = histories; this.events = events;
        this.archives = archives; this.policies = policies; this.content = content;
    }
    public record Entry(WorkflowInstance instance, WorkflowEvent event, FieldTarget target) { }

    public Entry require(String instanceId, String eventId, PlatformAction action) {
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 64
                || eventId == null || eventId.isBlank() || eventId.length() > 64
                || !TenantContext.hasContext() || CurrentUserContext.currentUser().isEmpty()
                || (action != PlatformAction.VIEW && action != PlatformAction.CONTENT_EDIT && action != PlatformAction.CONTENT_CONFIRM))
            throw denied();
        WorkflowInstance instance = WorkflowTenantScope.visible(instances.findById(instanceId));
        WorkflowEvent event;
        if (instance != null) {
            policies.requireRecordView(instance);
            var criteria = Criteria.of().eq("id", eventId).eq("instanceId", instanceId);
            if (instance.getTenantId() == null) criteria.isNull("tenantId"); else criteria.eq("tenantId", instance.getTenantId());
            event = events.query(criteria, PageRequest.of(1, 1)).stream().findFirst().orElseThrow(WorkflowOpinionAccess::denied);
        } else {
            var history = WorkflowTenantScope.visible(histories.findById(instanceId));
            if (history == null) throw denied();
            var snapshot = archives.parseSnapshot(history);
            instance = snapshot.instance();
            if (instance == null || !Objects.equals(instanceId, instance.getId())
                    || !Objects.equals(history.getTenantId(), instance.getTenantId())
                    || !Objects.equals(history.getModuleAlias(), instance.getModuleAlias())
                    || !Objects.equals(history.getRecordId(), instance.getRecordId())) throw denied();
            policies.requireRecordView(instance);
            event = snapshot.events().stream().filter(row -> eventId.equals(row.getId()))
                    .findFirst().orElseThrow(WorkflowOpinionAccess::denied);
        }
        if (!Objects.equals(instance.getId(), event.getInstanceId()) || !Objects.equals(instance.getTenantId(), event.getTenantId())
                || event.getReason() == null || event.getReason().isBlank()) throw denied();
        if (action != PlatformAction.VIEW) policies.requireRuntimeAction(instance, action.code());
        return new Entry(instance, event, content.target(instance.getModuleAlias(), event, "reason"));
    }
    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("Workflow opinion is unavailable");
    }
}
