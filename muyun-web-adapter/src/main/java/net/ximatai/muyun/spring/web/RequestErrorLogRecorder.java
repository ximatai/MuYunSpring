package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.LogText;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/** Records one safe request-error fact without allowing diagnostic storage to affect HTTP handling. */
public final class RequestErrorLogRecorder {
    private static final Logger log = LoggerFactory.getLogger(RequestErrorLogRecorder.class);
    static final String START_NANOS_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".START_NANOS";
    private static final String RECORDED_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".RECORDED";
    private static final String OPERATOR_ID_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".OPERATOR_ID";
    private static final String TENANT_ID_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".TENANT_ID";
    private static final String FILTER_ERROR_CODE_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".FILTER_ERROR_CODE";
    private static final String FILTER_RESPONSE_SUMMARY_ATTRIBUTE = RequestErrorLogRecorder.class.getName() + ".FILTER_RESPONSE_SUMMARY";
    private static final RequestErrorLogRecorder NOOP = new RequestErrorLogRecorder(null);
    private final BusinessLogPublisher publisher;

    public RequestErrorLogRecorder(BusinessLogPublisher publisher) {
        this.publisher = publisher;
    }

    public static RequestErrorLogRecorder noop() {
        return NOOP;
    }

    static void begin(HttpServletRequest request) {
        if (request.getAttribute(START_NANOS_ATTRIBUTE) == null) {
            request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        }
    }

    static void snapshotCurrentUser(HttpServletRequest request, CurrentUser user) {
        request.setAttribute(OPERATOR_ID_ATTRIBUTE, user.userId());
        if (user.tenantId() != null) {
            request.setAttribute(TENANT_ID_ATTRIBUTE, user.tenantId());
        }
    }

    static void snapshotTenant(HttpServletRequest request, String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            request.setAttribute(TENANT_ID_ATTRIBUTE, tenantId);
        }
    }

    static void markFilterResponse(HttpServletRequest request, String errorCode, int status, String safeMessage) {
        request.setAttribute(FILTER_ERROR_CODE_ATTRIBUTE, errorCode);
        request.setAttribute(FILTER_RESPONSE_SUMMARY_ATTRIBUTE,
                "code=" + errorCode + ";status=" + status + ";message=" + safeMessage);
    }

    public void recordException(PlatformWebError responseError, Throwable exception) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            record(attributes.getRequest(), responseError == null ? 500 : responseError.status(),
                    responseError == null ? null : responseError.code(), responseSummary(responseError),
                    exception, "EXCEPTION_HANDLER");
        }
    }

    public void recordResponseStatus(HttpServletRequest request, int status) {
        if (status >= 400 && status <= 599) {
            record(request, status, attribute(request, FILTER_ERROR_CODE_ATTRIBUTE),
                    attribute(request, FILTER_RESPONSE_SUMMARY_ATTRIBUTE), null, "FILTER_STATUS");
        }
    }

    public void recordUnhandled(HttpServletRequest request, Throwable exception) {
        record(request, 500, "INTERNAL_ERROR", "response=unavailable", exception, "FILTER_CHAIN_EXCEPTION");
    }

    private void record(HttpServletRequest request, int status, String errorCode, String responseSummary,
                        Throwable exception, String stage) {
        if (publisher == null || request == null || request.getAttribute(RECORDED_ATTRIBUTE) != null) {
            return;
        }
        request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
        try {
            var action = ActionExecutionContextHolder.current();
            var currentUser = CurrentUserContext.currentUser();
            String tenantId = attribute(request, TENANT_ID_ATTRIBUTE);
            if (tenantId == null) {
                tenantId = TenantContext.currentTenantId().orElseGet(() -> currentUser.map(user -> user.tenantId()).orElse(null));
            }
            String operatorId = attribute(request, OPERATOR_ID_ATTRIBUTE);
            if (operatorId == null) {
                operatorId = currentUser.map(user -> user.userId()).orElse(null);
            }
            String endpointId = action.map(value -> value.moduleAlias() + "." + value.actionCode()).orElse(null);
            String traceId = RequestTraceContext.currentTraceId().orElse(null);
            RequestErrorLogDetails details = new RequestErrorLogDetails(
                    request.getMethod(), requestPath(request), endpointId, elapsedMillis(request), status, errorCode,
                    LogText.of(responseSummary == null ? "status=" + status : responseSummary), exception == null ? null : exception.getClass().getName(),
                    exception == null ? null : LogText.of(exception.getMessage()),
                    exception == null ? null : LogText.of(stackTrace(exception)), stage,
                    // Servlet processing cannot establish that a client received the response bytes.
                    false);
            BusinessLogContext context = BusinessLogContext.capturedNow(Ids.newId(), Instant.now(), traceId,
                    tenantId, operatorId,
                    action.map(value -> value.moduleAlias()).orElse(null),
                    action.map(value -> value.actionCode()).orElse(null));
            publisher.publish(new RequestErrorLogEvent(context, details));
        } catch (RuntimeException ignored) {
            log.warn("Request error log publication failed");
        }
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri != null && contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri == null || requestUri.isBlank() ? "/" : requestUri;
    }

    private long elapsedMillis(HttpServletRequest request) {
        Object startedAt = request.getAttribute(START_NANOS_ATTRIBUTE);
        if (startedAt instanceof Long startedNanos) {
            return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
        return 0;
    }

    private String responseSummary(PlatformWebError error) {
        if (error == null) {
            return "response=unavailable";
        }
        String actionMessage = error.actionMessage() == null ? null
                : "actionCode=" + error.actionMessage().code() + ";actionMessage=" + error.actionMessage().text();
        return "code=" + error.code() + ";status=" + error.status() + ";message=" + error.message()
                + (actionMessage == null ? "" : ";" + actionMessage);
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String stackTrace(Throwable exception) {
        StringWriter output = new StringWriter();
        exception.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
