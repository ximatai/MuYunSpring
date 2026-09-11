package net.ximatai.muyun.spring.web;

import jakarta.servlet.DispatcherType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void shouldKeepValidInboundTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business");
        request.addHeader(RequestTraceContext.TRACE_ID_HEADER, "incoming-trace_1/part");
        AtomicReference<String> traceId = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                traceId.set(RequestTraceContext.currentTraceId().orElse(null)));

        assertThat(traceId.get()).isEqualTo("incoming-trace_1/part");
        assertThat(response.getHeader(RequestTraceContext.TRACE_ID_HEADER)).isEqualTo("incoming-trace_1/part");
    }

    @Test
    void shouldReplaceInvalidOrOversizedInboundTraceId() throws Exception {
        MockHttpServletRequest malformed = new MockHttpServletRequest("GET", "/business");
        malformed.addHeader(RequestTraceContext.TRACE_ID_HEADER, "not a valid trace id!");
        MockHttpServletResponse malformedResponse = new MockHttpServletResponse();
        AtomicReference<String> malformedTraceId = new AtomicReference<>();
        filter.doFilter(malformed, malformedResponse, (ignoredRequest, ignoredResponse) ->
                malformedTraceId.set(RequestTraceContext.currentTraceId().orElse(null)));

        MockHttpServletRequest oversized = new MockHttpServletRequest("GET", "/business");
        oversized.addHeader("X-Trace-Id", "x".repeat(129));
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
        AtomicReference<String> oversizedTraceId = new AtomicReference<>();
        filter.doFilter(oversized, oversizedResponse, (ignoredRequest, ignoredResponse) ->
                oversizedTraceId.set(RequestTraceContext.currentTraceId().orElse(null)));

        assertThat(malformedTraceId.get()).matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")
                .isNotEqualTo("not a valid trace id!");
        assertThat(oversizedTraceId.get()).hasSizeLessThanOrEqualTo(128)
                .isNotEqualTo("x".repeat(129));
        assertThat(malformedResponse.getHeader(RequestTraceContext.TRACE_ID_HEADER)).isEqualTo(malformedTraceId.get());
        assertThat(oversizedResponse.getHeader(RequestTraceContext.TRACE_ID_HEADER)).isEqualTo(oversizedTraceId.get());
    }

    @Test
    void shouldRecordAFilterShortCircuitWithVerifiedUserSnapshot() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        RequestTraceWebFilter tracedFilter = new RequestTraceWebFilter(new RequestErrorLogRecorder(publisher));
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "alice", "tenant-a", null, true);
        CurrentUserWebFilter currentUserFilter = new CurrentUserWebFilter(() -> Optional.of(currentUser));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business/blocked");
        MockHttpServletResponse response = new MockHttpServletResponse();

        tracedFilter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                currentUserFilter.doFilter(filteredRequest, filteredResponse,
                        (ignoredRequest, ignoredResponse) -> { throw new AssertionError("must short-circuit"); }));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(RequestErrorLogEvent.class, logged -> {
            assertThat(logged.context().operatorId()).isEqualTo("user-1");
            assertThat(logged.context().tenantId()).isEqualTo("tenant-a");
            assertThat(logged.details().errorCode()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
            assertThat(logged.details().responseSummary().value()).contains("password change required");
            assertThat(logged.details().failureStage()).isEqualTo("FILTER_STATUS");
            assertThat(logged.details().responseCompleted()).isFalse();
        });
    }

    @Test
    void shouldRecordUnhandledFilterChainFailureWithoutMaskingIt() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        RequestTraceWebFilter tracedFilter = new RequestTraceWebFilter(new RequestErrorLogRecorder(publisher));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business/failure");

        assertThatThrownBy(() -> tracedFilter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> { throw new IllegalStateException("password=secret"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(RequestErrorLogEvent.class, logged -> {
            assertThat(logged.details().httpStatus()).isEqualTo(500);
            assertThat(logged.details().failureStage()).isEqualTo("FILTER_CHAIN_EXCEPTION");
            assertThat(logged.details().exceptionMessage().value()).isEqualTo("password=[REDACTED]");
            assertThat(logged.details().responseCompleted()).isFalse();
        });
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
