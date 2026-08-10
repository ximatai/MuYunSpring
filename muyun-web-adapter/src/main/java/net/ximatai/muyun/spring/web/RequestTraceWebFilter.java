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

public class RequestTraceWebFilter extends OncePerRequestFilter implements Ordered {
    public static final String MDC_TRACE_ID = "traceId";
    private static final String TRACE_ID_ATTRIBUTE = RequestTraceWebFilter.class.getName() + ".TRACE_ID";

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
        String traceId = traceIdOf(request);
        try (RequestTraceContext.Scope ignored = RequestTraceContext.use(traceId)) {
            String effectiveTraceId = RequestTraceContext.ensureTraceId();
            request.setAttribute(TRACE_ID_ATTRIBUTE, effectiveTraceId);
            MDC.put(MDC_TRACE_ID, effectiveTraceId);
            response.setHeader(RequestTraceContext.TRACE_ID_HEADER, effectiveTraceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            RequestTraceContext.clear();
        }
    }

    private String traceIdOf(HttpServletRequest request) {
        Object carriedTraceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (carriedTraceId instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String traceId = request.getHeader(RequestTraceContext.TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return request.getHeader("X-Trace-Id");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
