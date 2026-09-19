package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleModelClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleCompletionAndConsumesSseDeltas() throws Exception {
        List<String> authorization = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, authorization));
        server.start();

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(new ObjectMapper());
        ResolvedAiModelRoute route = route();
        AiTextRequest request = AiTextRequest.userText("hello");

        AiTextResponse response = client.generate(route, request);
        StringBuilder streamed = new StringBuilder();
        client.stream(route, request, streamed::append);

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(streamed).hasToString("hello");
        assertThat(authorization).containsOnly("Bearer model-secret");
    }

    @Test
    void shouldRejectMalformedCompletionWithoutExposingResponseText() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "{\"choices\": private-provider-detail}");
        assertThatThrownBy(() ->
                client.generate(route(), AiTextRequest.userText("hello")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("invalid response").hasNoCause()
                .hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void shouldRejectProviderErrorsWithoutExposingTheirPayload() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "data: {\"error\":{\"message\":\"private-provider-detail\"}}\n\ndata: [DONE]\n\n");
        assertThatThrownBy(() ->
                client.stream(route(), AiTextRequest.userText("hello"), delta -> {}))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("rejected by provider")
                .hasNoCause().hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void shouldRejectAnInterruptedStreamAfterDeliveringItsPartialText() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        StringBuilder partial = new StringBuilder();
        assertThatThrownBy(() ->
                client.stream(route(), AiTextRequest.userText("hello"), partial::append))
                .hasMessageContaining("ended before completion");
        assertThat(partial).hasToString("partial");
    }

    @Test
    void shouldReadMultilineEventsAndIgnoreHeartbeatsAndUsageEvents() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                ": heartbeat\n\ndata:\n\ndata: {\n"
                        + "data: \"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n");
        StringBuilder text = new StringBuilder();
        client.stream(route(), AiTextRequest.userText("hello"), text::append);
        assertThat(text).hasToString("hello");
    }

    @Test
    void shouldRejectInvalidEventsWithoutExposingResponseText() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200, "data: private-invalid-response\n\n");
        assertThatThrownBy(() ->
                client.stream(route(), AiTextRequest.userText("hello"), delta -> {}))
                .hasMessageContaining("invalid event").hasNoCause().hasMessageNotContaining("private-invalid-response");
    }

    @Test
    void shouldRejectHttpFailuresBeforeConsumingStreamText() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(429, "private-provider-detail");
        assertThatThrownBy(() ->
                client.stream(route(), AiTextRequest.userText("hello"), delta -> {
                    throw new AssertionError("failed response must not produce deltas");
                })).hasMessageContaining("HTTP status 429").hasNoCause().hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void mapsCapabilityCodesToProviderSafeNamesAndRestoresStructuredCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                    + "{\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                    + "\"name\":\"capability_0\",\"arguments\":\"{\\\"query\\\":\\\"Alice\\\","
                    + "\\\"optional\\\":null,\\\"error\\\":\\\"business fact\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-request-id", "request-structured");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AiTurnRequest request = new AiTurnRequest(
                List.of(new AiChatMessage(AiChatMessage.Role.USER, "find Alice")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find a visible menu",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))),
                null, 512);

        AiTurnResponse response = new OpenAiCompatibleModelClient(new ObjectMapper()).complete(route(), request);

        assertThat(requestBody.get()).contains("\"name\":\"capability_0\"")
                .doesNotContain("workbench.find-menu");
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.code()).isEqualTo("workbench.find-menu");
            assertThat(call.arguments()).containsEntry("query", "Alice")
                    .containsEntry("error", "business fact").containsKey("optional");
            assertThat(call.arguments().get("optional")).isNull();
        });
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.requestId()).isEqualTo("request-structured");
    }

    @Test
    void rejectsProviderToolCallsThatWereNotDeclared() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{"
                        + "\"name\":\"capability_9\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, null);

        assertThatThrownBy(() -> client.complete(route(), request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("undeclared tool");
    }

    @Test
    void rejectsExcessiveStructuredToolCallsBeforeReturningThemToTheBrowser() throws Exception {
        String calls = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> "{\"id\":\"call-" + index
                        + "\",\"function\":{\"name\":\"capability_0\",\"arguments\":\"{}\"}}")
                .collect(java.util.stream.Collectors.joining(","));
        OpenAiCompatibleModelClient client = responseClient(200,
                "{\"choices\":[{\"message\":{\"tool_calls\":[" + calls
                        + "]},\"finish_reason\":\"tool_calls\"}]}");
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, null);

        assertThatThrownBy(() -> client.complete(route(), request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("too many tool calls");
    }

    @Test
    void rejectsOversizedToolArguments() throws Exception {
        String arguments = "x".repeat(65_537);
        String encodedArguments = new ObjectMapper().writeValueAsString(Map.of("value", arguments));
        OpenAiCompatibleModelClient client = responseClient(200,
                new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of(
                        "message", Map.of("tool_calls", List.of(Map.of(
                                "id", "call-1",
                                "function", Map.of("name", "capability_0", "arguments", encodedArguments)))),
                        "finish_reason", "tool_calls")))));
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, null);

        assertThatThrownBy(() -> client.complete(route(), request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("oversized tool arguments");
    }

    private OpenAiCompatibleModelClient responseClient(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new OpenAiCompatibleModelClient(new ObjectMapper());
    }

    private void respond(HttpExchange exchange, List<String> authorization) throws IOException {
        authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = request.contains("\"stream\":true");
        String body = stream
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}";
        exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ResolvedAiModelRoute route() {
        return new ResolvedAiModelRoute(AiModelProviderService.LM_STUDIO_ID,
                AiModelProtocol.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "local-model", "model-secret");
    }
}
