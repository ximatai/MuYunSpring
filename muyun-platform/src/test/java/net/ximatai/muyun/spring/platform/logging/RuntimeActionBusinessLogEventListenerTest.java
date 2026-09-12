package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.event.ActionEventPayload;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventListener;
import net.ximatai.muyun.spring.ability.event.RuntimeEventMulticaster;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RuntimeActionBusinessLogEventListenerTest {
    @Test
    void shouldConvertSuccessfulActionWithOriginalEventIdentityAndUnknownMetrics() {
        RecordingPublisher publisher = new RecordingPublisher();
        RuntimeActionBusinessLogEventListener listener = new RuntimeActionBusinessLogEventListener(publisher);
        RuntimeEvent event = event("event-1", "trace-1", RuntimeEventType.ACTION_EXECUTED,
                ActionEventPayload.executed("SERVICE", "COUNT", "submitted", false, null, false, 1));

        listener.onRuntimeEvent(event);

        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(ActionLogEvent.class, logged -> {
            assertThat(logged.context().eventId()).isEqualTo("event-1");
            assertThat(logged.context().traceId()).isEqualTo("trace-1");
            assertThat(logged.context().tenantId()).isEqualTo("tenant-a");
            assertThat(logged.context().operatorId()).isEqualTo("user-1");
            assertThat(logged.context().moduleAlias()).isEqualTo("sales.contract");
            assertThat(logged.context().actionCode()).isEqualTo("submit");
            assertThat(logged.details().outcome()).isEqualTo(net.ximatai.muyun.spring.ability.logging.ActionLogDetails.ActionOutcome.SUCCESS);
            assertThat(logged.details().executorType()).isEqualTo("SERVICE");
            assertThat(logged.details().durationMillis()).isNull();
            assertThat(logged.details().affectedRecordCount()).isNull();
            assertThat(logged.details().message().value()).isEqualTo("submitted");
        });
    }

    @Test
    void shouldCaptureCurrentUsersOrganizationOnlyWhenItMatchesRuntimeOperator() {
        RecordingPublisher publisher = new RecordingPublisher();
        RuntimeActionBusinessLogEventListener listener = new RuntimeActionBusinessLogEventListener(publisher);

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a", "organization-a"))) {
            listener.onRuntimeEvent(event("event-matching", "trace", RuntimeEventType.ACTION_EXECUTED, Map.of()));
        }
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("other-user", "Other", "tenant-a", "organization-b"))) {
            listener.onRuntimeEvent(event("event-mismatching", "trace", RuntimeEventType.ACTION_EXECUTED, Map.of()));
        }

        assertThat(publisher.events).extracting(event -> ((ActionLogEvent) event).context().operatorOrganizationId())
                .containsExactly("organization-a", null);
    }

    @Test
    void shouldConvertFailedActionAndRedactItsDiagnostic() {
        RecordingPublisher publisher = new RecordingPublisher();
        RuntimeActionBusinessLogEventListener listener = new RuntimeActionBusinessLogEventListener(publisher);
        RuntimeEvent event = event("event-2", "invalid trace id!", RuntimeEventType.ACTION_FAILED,
                ActionEventPayload.failed("FLOW", true, "PERSIST", "token=secret-value", "IllegalStateException"));

        listener.onRuntimeEvent(event);

        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(ActionLogEvent.class, logged -> {
            assertThat(logged.context().eventId()).isEqualTo("event-2");
            assertThat(logged.context().traceId()).isEqualTo("event-2");
            assertThat(logged.details().outcome()).isEqualTo(net.ximatai.muyun.spring.ability.logging.ActionLogDetails.ActionOutcome.FAILURE);
            assertThat(logged.details().failureStage()).isEqualTo("PERSIST");
            assertThat(logged.details().message().value()).isEqualTo("token=[REDACTED]");
        });
    }

    @Test
    void shouldIgnoreNonActionEventsAndNeverInterruptOtherRuntimeListenersWhenPublishingFails() {
        BusinessLogPublisher failingPublisher = new BusinessLogPublisher() {
            @Override public BusinessLogWriteResult publish(BusinessLogEvent event) { throw new IllegalStateException("down"); }
            @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) { throw new IllegalStateException("down"); }
        };
        RuntimeActionBusinessLogEventListener listener = new RuntimeActionBusinessLogEventListener(failingPublisher);
        AtomicInteger nextListenerCalls = new AtomicInteger();
        RuntimeEventListener next = ignored -> nextListenerCalls.incrementAndGet();

        assertThatCode(() -> new RuntimeEventMulticaster(List.of(listener, next))
                .publish(event("event-3", "trace-3", RuntimeEventType.ACTION_EXECUTED, Map.of())))
                .doesNotThrowAnyException();
        listener.onRuntimeEvent(new RuntimeEvent("event-4", "trace-4", RuntimeEventType.AFTER_UPDATE,
                "sales.contract", "contract", "contract-1", null, "tenant-a", false,
                RuntimeMutationSource.ACTION, Map.of(), Instant.now()));

        assertThat(nextListenerCalls).hasValue(1);
    }

    private RuntimeEvent event(String eventId, String traceId, RuntimeEventType type, Map<String, Object> payload) {
        return new RuntimeEvent(eventId, traceId, type, "sales.contract", "contract", "contract-1", "submit",
                "tenant-a", false, null, "user-1", "USER", "ALLOWED", RuntimeMutationSource.ACTION,
                payload, Instant.parse("2026-09-11T00:00:00Z"));
    }

    private static final class RecordingPublisher implements BusinessLogPublisher {
        private final List<BusinessLogEvent> events = new ArrayList<>();
        @Override public BusinessLogWriteResult publish(BusinessLogEvent event) {
            events.add(event);
            return new BusinessLogWriteResult(event.eventId(), BusinessLogWriteResult.Status.APPENDED);
        }
        @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) {
            return events.stream().map(this::publish).toList();
        }
    }
}
