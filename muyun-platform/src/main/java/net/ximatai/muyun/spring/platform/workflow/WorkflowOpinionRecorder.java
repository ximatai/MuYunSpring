package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentFacts;
import net.ximatai.muyun.spring.platform.content.BusinessContentSubmissionScope.Submission;
import net.ximatai.muyun.spring.platform.content.BusinessContentSubmissionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Captures the actual persisted opinion, separately from technical system messages and task decisions. */
@Service
public final class WorkflowOpinionRecorder {
    private final WorkflowInstanceDao instances;
    private final WorkflowEventDao events;
    private final BusinessContentFacts facts;
    private final WorkflowOpinionContentScope scope;
    private final BusinessContentSubmissionService submissions;
    public WorkflowOpinionRecorder(WorkflowInstanceDao instances, WorkflowEventDao events, BusinessContentFacts facts,
            WorkflowOpinionContentScope scope, BusinessContentSubmissionService submissions) {
        this.instances = java.util.Objects.requireNonNull(instances, "instances"); this.events = java.util.Objects.requireNonNull(events, "events"); this.facts = java.util.Objects.requireNonNull(facts, "facts"); this.scope = java.util.Objects.requireNonNull(scope, "scope"); this.submissions = java.util.Objects.requireNonNull(submissions, "submissions");
    }
    @EventListener
    public void persisted(WorkflowEventWriter.Persisted persisted) {
        if (persisted.event().getReason() == null || persisted.event().getReason().isBlank()) return;
        var event = events.findById(persisted.event().getId());
        if (event == null) throw new IllegalStateException("Workflow opinion was not persisted");
        if (event.getReason() == null || event.getReason().isBlank()) return;
        var instance = instances.findById(event.getInstanceId());
        if (instance == null || !Objects.equals(instance.getTenantId(), event.getTenantId()))
            throw new IllegalStateException("Persisted workflow opinion requires its owning instance");
        String authorName = CurrentUserContext.currentUser().filter(actor -> Objects.equals(actor.userId(), event.getOperatorId()))
                .map(actor -> actor.username()).orElse(null);
        var original = facts.record(facts.target(instance.getModuleAlias(), event, "reason"),
                event.getReasonSourceLanguage(), event.getOperatorId(), authorName, event.getOccurredAt());
        if (original.sourceLanguage() != null)
            submissions.recorded(new Submission(scope.capture(instance, event), instance.getModuleAlias(),
                    WorkflowOpinionContentScope.location(instance, event), "reason", original.revisionId()));
    }
}
