package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.Test;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises actual container error dispatch after the production identity filter closes its scope. */
@SpringBootTest(classes = StreamErrorBoundaryIT.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.context-path=/api", "spring.http.converters.preferred-json-mapper=jackson2"})
class StreamErrorBoundaryIT {
    @LocalServerPort
    int port;

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
    @Import({Endpoints.class, PlatformWebExceptionHandler.class})
    static class Application implements WebMvcConfigurer {
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
