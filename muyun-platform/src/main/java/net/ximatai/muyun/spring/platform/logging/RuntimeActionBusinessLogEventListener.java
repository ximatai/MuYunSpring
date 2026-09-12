package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.event.ActionEventPayload;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventListener;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.LogText;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Projects committed runtime action facts onto the neutral business-log boundary.
 * Publication is deliberately fail-open because runtime-event delivery occurs after the action
 * result has been committed.
 */
public final class RuntimeActionBusinessLogEventListener implements RuntimeEventListener {
    private static final Logger log = LoggerFactory.getLogger(RuntimeActionBusinessLogEventListener.class);
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private final BusinessLogPublisher publisher;

    public RuntimeActionBusinessLogEventListener(BusinessLogPublisher publisher) {
        this.publisher = java.util.Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void onRuntimeEvent(RuntimeEvent event) {
        if (event.eventType() != RuntimeEventType.ACTION_EXECUTED
                && event.eventType() != RuntimeEventType.ACTION_FAILED) {
            return;
        }
        try {
            publisher.publish(toActionLog(event));
        } catch (RuntimeException exception) {
            log.warn("Runtime action log publication failed");
        }
    }

    private ActionLogEvent toActionLog(RuntimeEvent event) {
        boolean failed = event.eventType() == RuntimeEventType.ACTION_FAILED;
        BusinessLogContext context = BusinessLogContext.capturedNow(
                event.eventId(), event.occurredAt(), safeTraceId(event.traceId()), event.tenantId(),
                event.operatorId(), operatorOrganizationId(event), event.moduleAlias(), event.actionCode());
        ActionLogDetails details = new ActionLogDetails(
                failed ? ActionLogDetails.ActionOutcome.FAILURE : ActionLogDetails.ActionOutcome.SUCCESS,
                ActionEventPayload.text(event.payload(), ActionEventPayload.EXECUTOR_TYPE),
                null,
                null,
                failed ? ActionEventPayload.text(event.payload(), ActionEventPayload.FAILURE_STAGE) : null,
                LogText.of(ActionEventPayload.text(event.payload(),
                        failed ? ActionEventPayload.ERROR_MESSAGE : ActionEventPayload.MESSAGE)));
        return new ActionLogEvent(context, details);
    }

    private String operatorOrganizationId(RuntimeEvent event) {
        return CurrentUserContext.currentUser()
                .filter(user -> user.userId().equals(event.operatorId()))
                .map(CurrentUser::organizationId)
                .orElse(null);
    }

    private String safeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank() || traceId.length() > 128) {
            return null;
        }
        return TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }
}
