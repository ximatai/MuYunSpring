package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnService;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnStreamConsumer;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises actual container error dispatch after the production identity filter closes its scope. */
@SpringBootTest(classes = StreamErrorBoundaryIT.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.context-path=/api", "spring.http.converters.preferred-json-mapper=jackson2"})
class StreamErrorBoundaryIT {
    @LocalServerPort
    int port;

    @Autowired
    AssistantTurnService assistant;

    @Test
    @Timeout(15)
    void assistantFailureAfterCommittedTextUsesAnSseErrorAndKeepsHttpStatus() throws Exception {
        var textReceived = new CountDownLatch(1);
        doAnswer(invocation -> {
            AssistantTurnStreamConsumer consumer = invocation.getArgument(1);
            consumer.onTextDelta("partial");
            if (!textReceived.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("text not received");
            }
            throw new PlatformException("MODEL_UNAVAILABLE", 502, "model unavailable");
        }).when(assistant).stream(any(), any());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/platform.assistant/turn/stream"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "text/event-stream, application/json")
                    .header("Content-Type", "application/json")
                    .header(MenuEntryRequestContext.HEADER_NAME, "menu.stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"inspect\"}"))
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(
                    response.body(), StandardCharsets.UTF_8))) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
                assertThat(reader.readLine()).isEqualTo("event:text");
                assertThat(reader.readLine()).contains("partial");
                assertThat(reader.readLine()).isEmpty();
                textReceived.countDown();
                assertThat(reader.readLine()).isEqualTo("event:error");
                var data = reader.readLine();
                assertThat(data).startsWith("data:");
                var error = new ObjectMapper().readTree(data.substring(5));
                assertThat(error.path("code").asText()).isEqualTo("MODEL_UNAVAILABLE");
                assertThat(error.path("status").asInt()).isEqualTo(502);
                assertThat(error.path("message").asText()).isEqualTo("model unavailable");
                assertThat(reader.readLine()).isEmpty();
                assertThat(reader.readLine()).isNull();
            } finally {
                textReceived.countDown();
            }
        }
    }

    @Test
    void preStreamRejectionsKeepTheirStatusAndJsonForBothAcceptHeaders() throws Exception {
        for (var accept : new String[]{"text/event-stream", "text/event-stream, application/json"}) {
            var response = get("/rejected", accept);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
            assertThat(new ObjectMapper().readTree(response.body()).path("code").asText()).isEqualTo("STREAM_REJECTED");
        }
    }

    @Test
    void containerErrorRenderingRetainsOriginalStatusWithoutReauthorizingMenu() throws Exception {
        var response = get("/container-error", "text/event-stream, application/json");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("X-Test-Error-Dispatch")).contains("true");
        assertThat(new ObjectMapper().readTree(response.body()).path("status").asInt()).isEqualTo(409);
    }

    @Test
    void successfulStreamStillDeliversSseData() throws Exception {
        var response = get("/stream", "text/event-stream, application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.body()).contains("event:complete", "data:done");
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", accept)
                    .header(MenuEntryRequestContext.HEADER_NAME, "menu.stream").build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
            "net.ximatai.muyun.database.spring.boot.MuYunDatabaseAutoConfiguration"
    })
    @Import({Endpoints.class, PlatformWebExceptionHandler.class, AssistantWebController.class})
    static class Application implements WebMvcConfigurer {
        @Bean
        AssistantTurnService assistantService() {
            return mock(AssistantTurnService.class);
        }

        @Bean
        CurrentUserWebFilter identityFilter() {
            return new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("viewer", "Viewer", "tenant-a")));
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            var menus = mock(MenuService.class);
            when(menus.currentUserVisibleMenu("menu.stream")).thenAnswer(invocation -> {
                if (CurrentUserContext.currentUser().isEmpty()) throw new AuthenticationRequiredException("identity scope closed");
                var menu = new Menu();
                menu.setId("menu.stream");
                menu.setModuleAlias("demo.stream");
                return menu;
            });
            registry.addInterceptor(new MenuEntryRequestInterceptor(menus));
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    if (request.getDispatcherType() == DispatcherType.ERROR) {
                        assertThat(CurrentUserContext.currentUser()).isEmpty();
                        assertThat(MenuEntryRequestContext.current()).isEmpty();
                        response.setHeader("X-Test-Error-Dispatch", "true");
                    }
                    return true;
                }
            });
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping(value = "/rejected", produces = "text/event-stream")
        SseEmitter rejected() {
            throw new PlatformException("STREAM_REJECTED", 409, "stream cannot start");
        }

        @GetMapping(value = "/container-error", produces = "text/event-stream")
        void containerError(HttpServletResponse response) throws IOException {
            assertThat(CurrentUserContext.currentUser()).isPresent();
            assertThat(MenuEntryRequestContext.current()).isPresent();
            response.sendError(409, "original conflict");
        }

        @GetMapping(value = "/stream", produces = "text/event-stream")
        SseEmitter stream() throws IOException {
            var emitter = new SseEmitter();
            emitter.send(SseEmitter.event().name("complete").data("done"));
            emitter.complete();
            return emitter;
        }
    }
}
