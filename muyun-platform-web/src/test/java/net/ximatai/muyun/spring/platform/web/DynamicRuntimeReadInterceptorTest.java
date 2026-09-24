package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.runtime.DynamicRuntimePublication;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DynamicRuntimeReadInterceptorTest {
    @Test
    void publicationWaitsFromDeserializationThroughResponseWriting() throws Exception {
        var publication = new DynamicRuntimePublication();
        var revision = new AtomicInteger(1);
        var phases = new PausingMessageAdvice();
        MockMvc mvc = standaloneSetup(new Records(revision)).setControllerAdvice(phases)
                .addInterceptors(new DynamicRuntimeReadInterceptor(publication)).build();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var request = executor.submit(() -> mvc.perform(post("/records").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.revision").value(1)));
                await(phases.reading);
                var published = executor.submit(() -> {
                    try (var ignored = publication.publication()) { revision.set(2); }
                });
                assertThatThrownBy(() -> published.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                phases.read.countDown();
                await(phases.writing);
                assertThatThrownBy(() -> published.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                phases.write.countDown();
                request.get(5, TimeUnit.SECONDS);
                published.get(5, TimeUnit.SECONDS);
                assertThat(revision).hasValue(2);
            } finally {
                phases.read.countDown();
                phases.write.countDown();
            }
        }
    }

    @Test
    void malformedInputAndControllerFailureReleaseThePublicationBoundary() throws Exception {
        var publication = new DynamicRuntimePublication();
        MockMvc mvc = standaloneSetup(new Records(new AtomicInteger(1)))
                .addInterceptors(new DynamicRuntimeReadInterceptor(publication)).build();
        mvc.perform(post("/records").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> mvc.perform(get("/records/fail"))).hasRootCauseMessage("record failed");
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> { try (var ignored = publication.publication()) { } }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void configurationEndpointsCanPublishAndUnsupportedAsyncReadersFailBeforeExecution() throws Exception {
        var publication = new DynamicRuntimePublication();
        var configuration = new Configuration(publication);
        MockMvc mvc = standaloneSetup(configuration, new Records(new AtomicInteger(1)))
                .addInterceptors(new DynamicRuntimeReadInterceptor(publication)).build();
        mvc.perform(post("/configuration")).andExpect(status().isOk());
        assertThatThrownBy(() -> mvc.perform(get("/records/async")))
                .hasRootCauseMessage("DynamicRuntimeRead requires a synchronous endpoint");
    }

    @DynamicRuntimeRead
    @RestController
    static class Records {
        private final AtomicInteger revision;
        Records(AtomicInteger revision) { this.revision = revision; }
        @PostMapping("/records") Map<String, Object> create(@RequestBody Payload payload) {
            return Map.of("name", payload.name(), "revision", revision.get());
        }
        @GetMapping("/records/fail") String fail() { throw new IllegalArgumentException("record failed"); }
        @GetMapping("/records/async") java.util.concurrent.Callable<String> async() {
            throw new AssertionError("asynchronous handler must not begin");
        }
    }

    record Payload(String name) { }

    @RestController
    static class Configuration {
        private final DynamicRuntimePublication publication;
        Configuration(DynamicRuntimePublication publication) { this.publication = publication; }
        @PostMapping("/configuration") String publish() {
            try (var ignored = publication.publication()) { return "published"; }
        }
    }

    @ControllerAdvice
    static class PausingMessageAdvice extends RequestBodyAdviceAdapter implements ResponseBodyAdvice<Object> {
        final CountDownLatch reading = new CountDownLatch(1), read = new CountDownLatch(1);
        final CountDownLatch writing = new CountDownLatch(1), write = new CountDownLatch(1);
        @Override public boolean supports(MethodParameter method, Type type, Class<? extends HttpMessageConverter<?>> converter) {
            return true;
        }
        @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter method,
                Type type, Class<? extends HttpMessageConverter<?>> converter) {
            reading.countDown(); await(read); return input;
        }
        @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
            return true;
        }
        @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
                Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
            writing.countDown(); await(write); return body;
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
