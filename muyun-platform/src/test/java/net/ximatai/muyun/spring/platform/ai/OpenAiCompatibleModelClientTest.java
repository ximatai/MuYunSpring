package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
    void terminatesAStreamThatSendsHeadersButNeverCompletesItsBody() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\" \"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var client = new OpenAiCompatibleModelClient(java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), java.time.Duration.ofMillis(100));
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, 512);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> client.stream(route(), request, new AiTurnStreamConsumer() {
                    public void onTextDelta(String text) { }
                    public void onComplete(AiTurnResponse result) { throw new AssertionError("Incomplete stream cannot complete"); }
                })).isInstanceOf(PlatformException.class).hasMessageContaining("timed out"));
        } finally { release.countDown(); }
    }

    @Test
    void serializesNativeHistoricalToolPairsAfterTheirCapabilityDisappears() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var request = new AiTurnRequest(List.of(
                new AiChatMessage(AiChatMessage.Role.USER, "fill draft"),
                AiChatMessage.call(new AiToolCall("old-call", "form.patch-draft", Map.of("title", "示例"))),
                AiChatMessage.result("old-call", "{\"execution\":\"effect-applied\"}"),
                new AiChatMessage(AiChatMessage.Role.USER, "current page")),
                List.of(new AiToolDefinition("query.describe", "read", Map.of("type", "object"))), null, 512);
        new OpenAiCompatibleModelClient(new ObjectMapper()).complete(route(), request);
        var json = new ObjectMapper().readTree(body.get());
        assertThat(json.path("messages").get(1).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo(OpenAiCompatibleModelClient.providerToolName("form.patch-draft"));
        assertThat(json.path("messages").get(2).path("role").asText()).isEqualTo("tool");
        assertThat(json.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("old-call");
        assertThat(json.path("messages").get(3).path("content").asText()).isEqualTo("current page");
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
                    + "\"name\":\"cap_d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab\",\"arguments\":\"{\\\"query\\\":\\\"Alice\\\","
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

        assertThat(requestBody.get()).contains("\"name\":\"cap_d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab\"")
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
    void streamsStructuredTextAndReassemblesFragmentedToolCallsBeforeCompletion() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "data: {\"choices\":[{\"delta\":{\"content\":\"正在\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"处理\",\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"call-1\",\"function\":{\"name\":\"cap_\",\"arguments\":\"{\\\"query\\\":\"}}]},"
                    + "\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab\","
                    + "\"arguments\":\"\\\"Alice\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("x-request-id", "request-streamed");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find Alice")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, 512);
        List<String> deltas = new ArrayList<>();
        AtomicReference<AiTurnResponse> completed = new AtomicReference<>();

        new OpenAiCompatibleModelClient(new ObjectMapper()).stream(route(), request, new AiTurnStreamConsumer() {
            @Override
            public void onTextDelta(String text) {
                deltas.add(text);
            }

            @Override
            public void onComplete(AiTurnResponse response) {
                completed.set(response);
            }
        });

        assertThat(requestBody.get()).contains("\"stream\":true", "\"name\":\"cap_d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab\"");
        assertThat(deltas).containsExactly("正在", "处理");
        assertThat(completed.get().text()).isEqualTo("正在处理");
        assertThat(completed.get().finishReason()).isEqualTo("tool_calls");
        assertThat(completed.get().requestId()).isEqualTo("request-streamed");
        assertThat(completed.get().toolCalls()).containsExactly(
                new AiToolCall("call-1", "workbench.find-menu", Map.of("query", "Alice")));
    }

    @Test
    void rejectsStructuredStreamsThatEndWithoutTerminalMarker() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n");
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "describe")),
                List.of(), null, 512);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> client.stream(route(), request, new AiTurnStreamConsumer() {
            @Override
            public void onTextDelta(String text) {
                deltas.add(text);
            }

            @Override
            public void onComplete(AiTurnResponse response) {
                throw new AssertionError("interrupted streams must not complete");
            }
        })).isInstanceOf(PlatformException.class).hasMessageContaining("ended before completion");
        assertThat(deltas).containsExactly("partial");
    }

    @Test
    void rejectsStructuredStreamHttpFailuresBeforeProducingEvents() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(429, "private-provider-detail");
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "describe")),
                List.of(), null, 512);

        assertThatThrownBy(() -> client.stream(route(), request, new AiTurnStreamConsumer() {
            @Override
            public void onTextDelta(String text) {
                throw new AssertionError("failed responses must not produce deltas");
            }

            @Override
            public void onComplete(AiTurnResponse response) {
                throw new AssertionError("failed responses must not complete");
            }
        })).isInstanceOf(PlatformException.class)
                .hasMessageContaining("HTTP status 429")
                .hasNoCause()
                .hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void rejectsOversizedStructuredStreamLinesWhileReading() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200, "data: " + "x".repeat(131_073));
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "describe")),
                List.of(), null, 512);

        assertThatThrownBy(() -> client.stream(route(), request, new AiTurnStreamConsumer() {
            @Override
            public void onTextDelta(String text) {
            }

            @Override
            public void onComplete(AiTurnResponse response) {
            }
        })).isInstanceOf(PlatformException.class).hasMessageContaining("oversized event");
    }

    @Test
    void normalizesEmptyObjectSchemasForStrictOpenAiCompatibleProviders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AiTurnRequest request = new AiTurnRequest(
                List.of(new AiChatMessage(AiChatMessage.Role.USER, "describe")),
                List.of(new AiToolDefinition("page.describe", "Describe page",
                        Map.of("type", "object", "additionalProperties", false))),
                null, 512);

        new OpenAiCompatibleModelClient(new ObjectMapper()).complete(route(), request);

        JsonNode sent = new ObjectMapper().readTree(requestBody.get());
        assertThat(sent.path("tools").path(0).path("function").path("parameters").path("properties").isObject())
                .isTrue();
    }

    @Test
    void preservesAnEmptyFinishedTurnForTheConversationLayerToInterpret() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"stop\"}]}");

        AiTurnResponse response = client.complete(route(), new AiTurnRequest(
                List.of(new AiChatMessage(AiChatMessage.Role.USER, "continue")), List.of(), null, 512));

        assertThat(response.text()).isNull();
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    @Test
    void rejectsStructuredResponsesWithoutAChoiceMessage() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200, "{\"choices\":[]}");

        assertThatThrownBy(() -> client.complete(route(), new AiTurnRequest(
                List.of(new AiChatMessage(AiChatMessage.Role.USER, "continue")), List.of(), null, 512)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("invalid structured response");
    }

    @Test
    void rejectsProviderToolCallsThatWereNotDeclared() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{"
                        + "\"name\":\"cap_9\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
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
                        + "\",\"function\":{\"name\":\"cap_d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab\",\"arguments\":\"{}\"}}")
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
                                "function", Map.of("name", "cap_d5add68fb09702059e5a040bde0a0e2da74598d772a22c68b36fe5ab", "arguments", encodedArguments)))),
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
