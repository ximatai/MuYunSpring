package net.ximatai.muyun.spring.web;

import jakarta.servlet.DispatcherType;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceWebFilterTest {
    private final RequestTraceWebFilter filter = new RequestTraceWebFilter();

    @AfterEach
    void tearDown() {
        RequestTraceContext.clear();
        MDC.clear();
    }

    @Test
    void shouldKeepTheInitialTraceIdWhenAsyncRequestIsRedispatched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business/stream");
        AtomicReference<String> initialTraceId = new AtomicReference<>();
        MockHttpServletResponse initialResponse = new MockHttpServletResponse();

        filter.doFilter(request, initialResponse, (ignoredRequest, ignoredResponse) ->
                initialTraceId.set(RequestTraceContext.currentTraceId().orElse(null)));

        request.setDispatcherType(DispatcherType.ASYNC);
        AtomicReference<String> resumedTraceId = new AtomicReference<>();
        MockHttpServletResponse resumedResponse = new MockHttpServletResponse();
        filter.doFilter(request, resumedResponse, (ignoredRequest, ignoredResponse) ->
                resumedTraceId.set(RequestTraceContext.currentTraceId().orElse(null)));

        assertThat(initialTraceId.get()).isNotBlank();
        assertThat(resumedTraceId.get()).isEqualTo(initialTraceId.get());
        assertThat(initialResponse.getHeader(RequestTraceContext.TRACE_ID_HEADER)).isEqualTo(initialTraceId.get());
        assertThat(resumedResponse.getHeader(RequestTraceContext.TRACE_ID_HEADER)).isEqualTo(initialTraceId.get());
        assertThat(RequestTraceContext.currentTraceId()).isEmpty();
        assertThat(MDC.get(RequestTraceWebFilter.MDC_TRACE_ID)).isNull();
    }
}
