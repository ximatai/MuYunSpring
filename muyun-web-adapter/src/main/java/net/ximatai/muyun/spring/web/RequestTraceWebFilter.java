package net.ximatai.muyun.spring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

public class RequestTraceWebFilter extends OncePerRequestFilter implements Ordered {
    public static final String MDC_TRACE_ID = "traceId";
    private static final String TRACE_ID_ATTRIBUTE = RequestTraceWebFilter.class.getName() + ".TRACE_ID";
    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private final RequestErrorLogRecorder requestErrorLogRecorder;

    public RequestTraceWebFilter() {
        this(RequestErrorLogRecorder.noop());
    }

    public RequestTraceWebFilter(RequestErrorLogRecorder requestErrorLogRecorder) {
        this.requestErrorLogRecorder = requestErrorLogRecorder == null ? RequestErrorLogRecorder.noop() : requestErrorLogRecorder;
    }

    /**
     * Keep the same trace when MVC resumes an asynchronous request on another Servlet dispatch.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestErrorLogRecorder.begin(request);
        String traceId = traceIdOf(request);
        try (RequestTraceContext.Scope ignored = RequestTraceContext.use(traceId)) {
            String effectiveTraceId = RequestTraceContext.ensureTraceId();
            request.setAttribute(TRACE_ID_ATTRIBUTE, effectiveTraceId);
            MDC.put(MDC_TRACE_ID, effectiveTraceId);
            response.setHeader(RequestTraceContext.TRACE_ID_HEADER, effectiveTraceId);
            try {
                filterChain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException exception) {
                requestErrorLogRecorder.recordUnhandled(request, exception);
                throw exception;
            } finally {
                requestErrorLogRecorder.recordResponseStatus(request, response.getStatus());
            }
        } finally {
            MDC.remove(MDC_TRACE_ID);
            RequestTraceContext.clear();
        }
    }

    private String traceIdOf(HttpServletRequest request) {
        Object carriedTraceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (carriedTraceId instanceof String traceId && !traceId.isBlank()) {
            return normalizeTraceId(traceId);
        }
        String traceId = request.getHeader(RequestTraceContext.TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            return normalizeTraceId(traceId);
        }
        return normalizeTraceId(request.getHeader("X-Trace-Id"));
    }

    private String normalizeTraceId(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_TRACE_ID_LENGTH) {
            return null;
        }
        return TRACE_ID.matcher(candidate).matches() ? candidate : null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
