package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Fail-open observer for completed static CrudWeb mutations; dynamic actions use RuntimeEvent instead. */
public final class StaticCrudActionLogRecorder {
    private static final Logger log = LoggerFactory.getLogger(StaticCrudActionLogRecorder.class);
    static final String START_NANOS = StaticCrudActionLogRecorder.class.getName() + ".START_NANOS";
    private final BusinessLogPublisher publisher;

    public StaticCrudActionLogRecorder(BusinessLogPublisher publisher) { this.publisher = publisher; }

    public void begin(HttpServletRequest request) { request.setAttribute(START_NANOS, System.nanoTime()); }

    public void record(HttpServletRequest request, net.ximatai.muyun.spring.common.platform.ActionExecutionContext context,
                       Exception failure) {
        if (publisher == null) return;
        try {
            long duration = request.getAttribute(START_NANOS) instanceof Long started
                    ? Math.max(0, (System.nanoTime() - started) / 1_000_000L) : 0;
            String tenantId = TenantContext.currentTenantId()
                    .orElseGet(() -> context.currentUser().map(user -> user.tenantId()).orElse(null));
            publisher.publish(new ActionLogEvent(BusinessLogContext.capturedNow(Ids.newId(), Instant.now(),
                    RequestTraceContext.currentTraceId().orElse(null), tenantId,
                    context.currentUser().map(user -> user.userId()).orElse(null), context.moduleAlias(), context.actionCode()),
                    new ActionLogDetails(failure == null ? ActionLogDetails.ActionOutcome.SUCCESS : ActionLogDetails.ActionOutcome.FAILURE,
                            "STATIC_CRUD", duration, null, failure == null ? null : "CONTROLLER", null)));
        } catch (RuntimeException ignored) { log.warn("Static CRUD action log publication failed"); }
    }
}
