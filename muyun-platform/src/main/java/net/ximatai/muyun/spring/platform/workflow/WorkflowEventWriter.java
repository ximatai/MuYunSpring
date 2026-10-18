package net.ximatai.muyun.spring.platform.workflow;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** The workflow fact is persisted before adapters capture provenance or schedule reading projections. */
@Service
public final class WorkflowEventWriter {
    private final WorkflowEventDao events;
    private final ApplicationEventPublisher publisher;
    public WorkflowEventWriter(WorkflowEventDao events, ApplicationEventPublisher publisher) {
        this.events = Objects.requireNonNull(events); this.publisher = Objects.requireNonNull(publisher);
    }
    public void insert(WorkflowEvent event) {
        events.insert(event);
        publisher.publishEvent(new Persisted(event));
    }
    public record Persisted(WorkflowEvent event) { }
}
