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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void rejectsIndexedCallsAndOnlyReportsKnownMissingDeclarations(boolean stream, boolean known, boolean throughGateway) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var calls = List.of(
                    Map.of("id", "valid", "type", "function", "index", 0,
                            "function", Map.of("name", "cap_page_patch", "arguments", "{}")),
                    Map.of("id", "missing", "type", "function", "index", 1,
                            "function", Map.of("name", known ? "cap_page_next" : "private-provider-name",
                                    "arguments", "{\"private\":\"untrusted output\"}")));
            String body = stream ? "data: " + mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", calls), "finish_reason", "tool_calls")))) + "\n\ndata: " + mapper.writeValueAsString(Map.of("choices", List.of(), "usage", Map.of("total_tokens", 17))) + "\n\ndata: [DONE]\n\n"
                    : mapper.writeValueAsString(Map.of("usage", Map.of("total_tokens", 17), "choices", List.of(Map.of(
                    "message", Map.of("tool_calls", calls), "finish_reason", "tool_calls"))));
            if (stream) exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "continue")),
                List.of(new AiToolDefinition("page.patch", "Patch", Map.of("type", "object"))),
                null, 512, List.of("page.next"));
        AiTurnStreamConsumer consumer = org.mockito.Mockito.mock(AiTurnStreamConsumer.class);
        assertThatThrownBy(() -> {
            var client = new OpenAiCompatibleModelClient(mapper);
            if (throughGateway) {
                var resolver = org.mockito.Mockito.mock(AiModelRouteResolver.class);
                org.mockito.Mockito.when(resolver.resolveCurrent()).thenReturn(route());
                var gateway = new DefaultAiModelGateway(resolver, client);
                if (stream) gateway.stream(request, consumer);
                else gateway.complete(request);
            } else {
                if (stream) client.stream(route(), request, consumer);
                else client.complete(route(), request);
            }
        }).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.code()).isEqualTo("AI_MODEL_UNDECLARED_TOOL");
            assertThat(error.details()).containsEntry("modelToolCallCount", 2).containsEntry("modelUsage", new AiTokenUsage(null, null, 17L));
            assertThat(error.details().keySet()).containsExactlyInAnyOrderElementsOf(known
                    ? List.of("missingToolCodes", "modelToolCallCount", "modelUsage") : List.of("modelToolCallCount", "modelUsage"));
            if (known) assertThat(error.details()).containsEntry("missingToolCodes", List.of("page.next"));
            assertThat(error.details().toString()).doesNotContain("private-provider-name", "untrusted output");
            assertThat(error.getMessage()).doesNotContain("private-provider-name", "untrusted output");
        });
        org.mockito.Mockito.verifyNoInteractions(consumer);
        assertThat(mapper.readTree(requestBody.get()).path("tools")).hasSize(1);
        assertThat(requestBody.get()).doesNotContain("page.next", "indexedToolCodes");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void duplicateProviderIdsRetainUsageWithoutExposingIdentifiers(boolean stream) throws Exception {
        var mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> Map.of(
                    "id", "private-provider-id", "index", index, "type", "function",
                    "function", Map.of("name", "cap_page_patch", "arguments", "{}"))).toList();
            String body = stream ? "data: " + mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", calls), "finish_reason", "tool_calls"))))
                    + "\n\ndata: " + mapper.writeValueAsString(Map.of("choices", List.of(), "usage", Map.of("total_tokens", 17)))
                    + "\n\ndata: [DONE]\n\n"
                    : mapper.writeValueAsString(Map.of("usage", Map.of("total_tokens", 17), "choices", List.of(Map.of(
                    "message", Map.of("tool_calls", calls), "finish_reason", "tool_calls"))));
            if (stream) exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "continue")),
                List.of(new AiToolDefinition("page.patch", "Patch", Map.of("type", "object"))), null, 512);
        var consumer = org.mockito.Mockito.mock(AiTurnStreamConsumer.class);
        var client = new OpenAiCompatibleModelClient(mapper);
        assertThatThrownBy(() -> {
            if (stream) client.stream(route(), request, consumer);
            else client.complete(route(), request);
        }).isInstanceOfSatisfying(PlatformException.class, failure -> {
            assertThat(failure.details()).containsEntry("modelToolCallCount", 2)
                    .containsEntry("modelUsage", new AiTokenUsage(null, null, 17L));
            assertThat(failure.getMessage()).doesNotContain("private-provider-id");
            assertThat(failure.details().toString()).doesNotContain("private-provider-id");
        });
        org.mockito.Mockito.verifyNoInteractions(consumer);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "text,inherit", "text,none", "text,low", "turn,inherit", "turn,none", "turn,low",
            "text-stream,inherit", "text-stream,none", "text-stream,low", "turn-stream,inherit", "turn-stream,none", "turn-stream,low"})
    void transmitsOnlyExplicitDeploymentReasoningEffortAcrossTextAndToolTransports(String mode, String effort) throws Exception {
        var body = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode sent = mapper.readTree(exchange.getRequestBody()); body.set(sent);
            boolean streaming = sent.path("stream").asBoolean();
            String response = streaming
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}";
            exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var baseline = route();
        var selected = new ResolvedAiModelRoute(baseline.provider(), baseline.protocol(), baseline.baseUrl(),
                baseline.modelId(), baseline.apiKey(), baseline.limits(),
                effort.equals("inherit") ? null : AiModelReasoningEffort.valueOf(effort.toUpperCase(java.util.Locale.ROOT)));
        var client = new OpenAiCompatibleModelClient(mapper);
        var text = AiTextRequest.userText("hello");
        var turn = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, 512);
        switch (mode) {
            case "text" -> client.generate(selected, text);
            case "turn" -> client.complete(selected, turn);
            case "text-stream" -> client.stream(selected, text, delta -> {});
            case "turn-stream" -> client.stream(selected, turn, org.mockito.Mockito.mock(AiTurnStreamConsumer.class));
            default -> throw new AssertionError(mode);
        }
        if (effort.equals("inherit")) assertThat(body.get().has("reasoning_effort")).isFalse();
        else assertThat(body.get().path("reasoning_effort").asText()).isEqualTo(effort);
        assertThat(body.get().has("thinking")).isFalse();
        assertThat(body.get().path("model").asText()).isEqualTo("local-model");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void finishDiagnosticsOnlyExposeKnownProtocolValues() {
        assertThat(OpenAiCompatibleModelClient.safeFinishReason("length")).isEqualTo("length");
        assertThat(OpenAiCompatibleModelClient.safeFinishReason("tool_calls")).isEqualTo("tool_calls");
        assertThat(OpenAiCompatibleModelClient.safeFinishReason(null)).isEqualTo("missing");
        assertThat(OpenAiCompatibleModelClient.safeFinishReason("private provider payload\nsecret"))
                .isEqualTo("other");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "upstream_unavailable,server_error,503", "invalid_api_key,authentication_error,401",
            "rate_limit_exceeded,rate_limit_error,429", "content_filter,invalid_request_error,400"})
    void errorDiagnosticsPreserveOnlyRegisteredProtocolHints(String code, String type, int status) {
        var error = new ObjectMapper().valueToTree(Map.of("code", code, "type", type, "status", status,
                "message", "private provider detail", "credential", "secret"));
        assertThat(OpenAiCompatibleModelClient.providerErrorDiagnostics(error))
                .containsOnlyKeys("shape", "code", "type", "reportedStatus")
                .containsEntry("shape", "object").containsEntry("code", code)
                .containsEntry("type", type).containsEntry("reportedStatus", status);
    }

    @Test
    void errorDiagnosticsHideUnknownValuesAndRejectMalformedStatusHints() throws Exception {
        var mapper = new ObjectMapper();
        var privateError = mapper.readTree("{\"code\":\"private\\ncredential\",\"type\":\"secret\",\"status\":\"503\"}");
        assertThat(OpenAiCompatibleModelClient.providerErrorDiagnostics(privateError))
                .containsEntry("code", "other").containsEntry("type", "other").containsEntry("reportedStatus", "invalid");
        for (String payload : List.of("{\"code\":{},\"type\":[],\"status\":600}",
                "{\"code\":true,\"type\":1,\"status\":99}", "{\"code\":{},\"type\":[],\"status\":503.0}",
                "{\"code\":{},\"type\":[],\"status\":9999999999999}")) {
            assertThat(OpenAiCompatibleModelClient.providerErrorDiagnostics(mapper.readTree(payload)))
                    .containsEntry("shape", "object").containsEntry("code", "invalid")
                    .containsEntry("type", "invalid").containsEntry("reportedStatus", "invalid");
        }
        for (String payload : List.of("{}", "null", "[]", "\"private payload\"")) {
            assertThat(OpenAiCompatibleModelClient.providerErrorDiagnostics(mapper.readTree(payload)))
                    .containsEntry("shape", payload.equals("{}") ? "object" : "invalid")
                    .containsEntry("code", "missing").containsEntry("type", "missing").containsEntry("reportedStatus", "missing");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "text,upstream_unavailable,AI_PROVIDER_UNAVAILABLE,503",
            "turn,upstream_unavailable,AI_PROVIDER_UNAVAILABLE,503",
            "text-stream,upstream_unavailable,AI_PROVIDER_UNAVAILABLE,503",
            "turn-stream,upstream_unavailable,AI_PROVIDER_UNAVAILABLE,503",
            "text,private-code,AI_PROVIDER_REQUEST_REJECTED,502",
            "turn,private-code,AI_PROVIDER_REQUEST_REJECTED,502",
            "text-stream,private-code,AI_PROVIDER_REQUEST_REJECTED,502",
            "turn-stream,private-code,AI_PROVIDER_REQUEST_REJECTED,502"})
    void allTransportsClassifyKnownBodyOutageWithoutRetryOrExposingProviderPayload(
            String transport, String providerCode, String platformCode, int platformStatus) throws Exception {
        String error = "{\"error\":{\"code\":\"" + providerCode + "\",\"type\":\"private type\","
                + "\"status\":503,\"message\":\"private provider secret\"}}";
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var client = responseClient(200, transport.endsWith("stream") ? "data: " + error + "\n\n" : error, requests);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var text = AiTextRequest.userText("hello");
            var turn = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, null);
            assertThatThrownBy(() -> {
                switch (transport) {
                    case "text" -> client.generate(route(), text);
                    case "turn" -> client.complete(route(), turn);
                    case "text-stream" -> client.stream(route(), text, delta -> { throw new AssertionError(); });
                    case "turn-stream" -> client.stream(route(), turn, new AiTurnStreamConsumer() {
                        public void onTextDelta(String delta) { throw new AssertionError(); }
                        public void onComplete(AiTurnResponse result) { throw new AssertionError(); }
                    });
                    default -> throw new AssertionError();
                }
            }).isInstanceOf(PlatformException.class).hasNoCause().hasMessageNotContaining("private")
                    .satisfies(failure -> {
                        assertThat(((PlatformException) failure).code()).isEqualTo(platformCode);
                        assertThat(((PlatformException) failure).httpStatus()).isEqualTo(platformStatus);
                    });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        assertThat(events.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("transport=body httpStatus=200",
                        "code=" + (providerCode.equals("upstream_unavailable") ? "upstream_unavailable" : "other"),
                        "type=other", "reportedStatus=503"))
                .allSatisfy(message -> assertThat(message).doesNotContain("private", "secret"));
        assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void guardsAllTransportsBeforeSendingWithoutDroppingToolsOrMessages() {
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());
        var route = new ResolvedAiModelRoute("provider", AiModelProtocol.OPENAI_COMPATIBLE,
                "http://127.0.0.1:1/v1", "model", "secret", new AiModelLimits(4096, 1024, 512));
        var text = AiTextRequest.userText("客户".repeat(2000));
        var turn = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "你好")),
                List.of(new AiToolDefinition("describe", "字段".repeat(2000), Map.of("type", "object"))), null, null);
        assertThatThrownBy(() -> client.generate(route, text)).hasMessageContaining("上下文预算");
        assertThatThrownBy(() -> client.stream(route, text, delta -> {})).hasMessageContaining("上下文预算");
        assertThatThrownBy(() -> client.complete(route, turn)).hasMessageContaining("上下文预算");
        assertThatThrownBy(() -> client.stream(route, turn, new AiTurnStreamConsumer() {
            public void onTextDelta(String delta) { throw new AssertionError(); }
            public void onComplete(AiTurnResponse response) { throw new AssertionError(); }
        })).hasMessageContaining("上下文预算");
    }

    @Test
    void preservesUsageIncludingFinalEmptyChoiceStreamingEvents() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            assertThat(body.path("max_tokens").asInt()).isEqualTo(16384);
            if (body.path("stream").asBoolean()) assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
            String usage = "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":10,\"total_tokens\":52}";
            String response = body.path("stream").asBoolean()
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[]," + usage + "}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]," + usage + "}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var plainRoute = route();
        var configured = new ResolvedAiModelRoute(plainRoute.provider(), plainRoute.protocol(), plainRoute.baseUrl(),
                plainRoute.modelId(), plainRoute.apiKey(), new AiModelLimits(131072, 32768, 16384));
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());
        var text = AiTextRequest.userText("hello");
        var turn = new AiTurnRequest(text.messages(), List.of(), null, null);
        var expected = new AiTokenUsage(42L, 10L, 52L);
        assertThat(client.generate(configured, text).usage()).isEqualTo(expected);
        assertThat(client.complete(configured, turn).usage()).isEqualTo(expected);
        var completed = new AtomicReference<AiTurnResponse>();
        client.stream(configured, turn, new AiTurnStreamConsumer() {
            public void onTextDelta(String delta) { }
            public void onComplete(AiTurnResponse response) { completed.set(response); }
        });
        assertThat(completed.get().usage()).isEqualTo(expected);
        var deltas = new StringBuilder();
        client.stream(configured, text, deltas::append);
        assertThat(deltas.toString()).isEqualTo("OK");
    }

    @Test
    void explicitlyDisablesToolChoiceWhenNoToolsAreDeclared() throws Exception {
        var requestBody = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"summary\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        new OpenAiCompatibleModelClient(new ObjectMapper()).complete(route(), new AiTurnRequest(
                List.of(new AiChatMessage(AiChatMessage.Role.USER, "summarize")), List.of(), null, 512));
        var sent = new ObjectMapper().readTree(requestBody.get());
        assertThat(sent.path("tool_choice").asText()).isEqualTo("none");
        assertThat(sent.has("tools")).isFalse();
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
                })).isInstanceOf(PlatformException.class).hasMessageContaining("超时").satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_TIMEOUT")));
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
                .hasMessageContaining("模型服务拒绝了本次请求").satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_PROVIDER_REQUEST_REJECTED"))
                .hasNoCause().hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void shouldRejectAnInterruptedStreamAfterDeliveringItsPartialText() throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        StringBuilder partial = new StringBuilder();
        assertThatThrownBy(() ->
                client.stream(route(), AiTextRequest.userText("hello"), partial::append))
                .hasMessageContaining("完成前断开").hasMessageNotContaining("当前页面")
                .satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_INCOMPLETE_RESPONSE"));
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
                })).hasMessageContaining("HTTP 429").hasNoCause().hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void providerNamesRemainReadableBoundedAndDistinctAfterNormalization() {
        String code = "relation.reference.resolve-and-patch";
        assertThat(OpenAiCompatibleModelClient.providerToolName(code))
                .isEqualTo("cap_relation_reference_resolve-and-patch")
                .matches("[a-zA-Z0-9_-]{1,64}");
        assertThat(OpenAiCompatibleModelClient.providerToolName("a.b"))
                .isNotEqualTo(OpenAiCompatibleModelClient.providerToolName("a_b"));
        assertThat(OpenAiCompatibleModelClient.providerToolName("a_b"))
                .startsWith("capx_").matches("[a-zA-Z0-9_-]{1,64}")
                .isNotEqualTo(OpenAiCompatibleModelClient.providerToolName("a b"));
        String prefix = "long".repeat(30);
        assertThat(OpenAiCompatibleModelClient.providerToolName(prefix + "a"))
                .hasSize(64).isNotEqualTo(OpenAiCompatibleModelClient.providerToolName(prefix + "b"));
    }

    @Test
    void mapsCapabilityCodesToProviderSafeNamesAndRestoresStructuredCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                    + "{\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                    + "\"name\":\"cap_workbench_find-menu\",\"arguments\":\"{\\\"query\\\":\\\"Alice\\\","
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

        assertThat(requestBody.get()).contains("\"name\":\"cap_workbench_find-menu\"")
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
    void readableAndFallbackDeclarationsRoundTripWithoutNormalizedNameCollisions() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            List<Map<String, Object>> calls = new ArrayList<>();
            for (int index = 0; index < request.path("tools").size(); index++) {
                String name = request.path("tools").get(index).path("function").path("name").asText();
                calls.add(Map.of("id", "call-" + index, "type", "function",
                        "function", Map.of("name", name, "arguments", "{}")));
            }
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                    "message", Map.of("tool_calls", calls), "finish_reason", "tool_calls"))));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "read")),
                List.of(new AiToolDefinition("a.b", "read", Map.of("type", "object")),
                        new AiToolDefinition("a_b", "read", Map.of("type", "object")),
                        new AiToolDefinition("a b", "read", Map.of("type", "object"))), null, 512);

        assertThat(new OpenAiCompatibleModelClient(mapper).complete(route(), request).toolCalls())
                .extracting(AiToolCall::code).containsExactly("a.b", "a_b", "a b");
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
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"workbench_find-menu\","
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

        assertThat(requestBody.get()).contains("\"stream\":true", "\"name\":\"cap_workbench_find-menu\"");
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
        })).isInstanceOf(PlatformException.class).hasMessageContaining("完成前断开").hasMessageNotContaining("当前页面")
                .satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_INCOMPLETE_RESPONSE"));
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
                .hasMessageContaining("HTTP 429")
                .hasNoCause()
                .hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void acceptsSmallContentWithLargeStreamingEnvelope() throws Exception {
        String event = "data: {\"id\":\"" + "m".repeat(180) + "\",\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n";
        var client = responseClient(200, event.repeat(5000) + "data: [DONE]\n\n");
        var result = new AtomicReference<AiTurnResponse>();
        client.stream(route(), new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, 8192), new AiTurnStreamConsumer() {
            public void onTextDelta(String text) {}
            public void onComplete(AiTurnResponse response) { result.set(response); }
        });
        assertThat(result.get().text()).isEqualTo("好".repeat(5000));
    }

    @Test
    void rejectsAccumulatedContentEvenWhenEachEventIsSmall() throws Exception {
        String event = "data: {\"choices\":[{\"delta\":{\"content\":\"" + "x".repeat(32_768) + "\"}}]}\n\n";
        var client = responseClient(200, event.repeat(33) + "data: [DONE]\n\n");
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, 8192);
        assertThatThrownBy(() -> client.stream(route(), request, new AiTurnStreamConsumer() {
            public void onTextDelta(String text) {}
            public void onComplete(AiTurnResponse response) { throw new AssertionError("must not complete"); }
        })).isInstanceOf(PlatformException.class).hasMessageContaining("oversized structured response");
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
                .hasMessageContaining("undeclared tool")
                .satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_UNDECLARED_TOOL"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "cap_workbench_find-menu_d5add68fb097", "cap_workbench_open-menu"})
    void rejectsOldOrUndeclaredNamesInsteadOfGuessingTheirCapability(String name) throws Exception {
        OpenAiCompatibleModelClient client = responseClient(200,
                new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of(
                        "message", Map.of("tool_calls", List.of(Map.of("id", "call-1",
                                "function", Map.of("name", name, "arguments", "{}")))),
                        "finish_reason", "tool_calls")))));
        var request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, 512);

        assertThatThrownBy(() -> client.complete(route(), request))
                .isInstanceOf(PlatformException.class)
                .satisfies(error -> assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_UNDECLARED_TOOL"));
    }

    @Test
    void rejectsExcessiveStructuredToolCallsBeforeReturningThemToTheBrowser() throws Exception {
        String calls = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> "{\"id\":\"call-" + index
                        + "\",\"function\":{\"name\":\"cap_workbench_find-menu\",\"arguments\":\"{}\"}}")
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
                                "function", Map.of("name", "cap_workbench_find-menu", "arguments", encodedArguments)))),
                        "finish_reason", "tool_calls")))));
        AiTurnRequest request = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "find")),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menu", Map.of("type", "object"))),
                null, null);

        assertThatThrownBy(() -> client.complete(route(), request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("oversized tool arguments");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text", "turn", "text-stream", "turn-stream"})
    void retriesUpstreamFailureOnceWithIdenticalRequestAcrossTransports(String mode) throws Exception {
        var requests = new ArrayList<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean first = requests.size() == 1;
            String response = first ? "{\"error\":{\"code\":\"upstream_unavailable\",\"message\":\"private detail\"}}"
                    : mode.endsWith("stream") ? "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());
        var text = AiTextRequest.userText("hello");
        var turn = new AiTurnRequest(text.messages(), List.of(), null, null);
        var output = new StringBuilder();
        switch (mode) {
            case "text" -> output.append(client.generate(route(), text).text());
            case "turn" -> output.append(client.complete(route(), turn).text());
            case "text-stream" -> client.stream(route(), text, output::append);
            case "turn-stream" -> client.stream(route(), turn, new AiTurnStreamConsumer() {
                public void onTextDelta(String delta) { output.append(delta); }
                public void onComplete(AiTurnResponse response) { assertThat(response.text()).isEqualTo("OK"); }
            });
            default -> throw new AssertionError(mode);
        }
        assertThat(output.toString()).isEqualTo("OK");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).isEqualTo(requests.get(0));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "503,upstream_unavailable,2,AI_PROVIDER_UNAVAILABLE,503",
            "500,upstream_unavailable,2,AI_PROVIDER_UNAVAILABLE,503",
            "502,upstream_unavailable,2,AI_PROVIDER_UNAVAILABLE,503",
            "504,upstream_unavailable,2,AI_PROVIDER_UNAVAILABLE,503",
            "401,upstream_unavailable,1,AI_PROVIDER_AUTHENTICATION_FAILED,502",
            "403,upstream_unavailable,1,AI_PROVIDER_AUTHENTICATION_FAILED,502",
            "429,upstream_unavailable,1,AI_PROVIDER_RATE_LIMITED,429",
            "400,upstream_unavailable,1,AI_PROVIDER_REQUEST_REJECTED,502",
            "503,invalid_api_key,1,AI_PROVIDER_UNAVAILABLE,503"})
    void boundsRetriesAndDoesNotRetryOtherProviderErrors(int status, String code, int expected,
                                                       String errorCode, int platformStatus) throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            count.incrementAndGet();
            byte[] bytes = ("{\"error\":{\"code\":\"" + code + "\",\"message\":\"private detail\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());
        assertThatThrownBy(() -> client.generate(route(), AiTextRequest.userText("hello")))
                .isInstanceOf(PlatformException.class).hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining("private detail").hasNoCause()
                .satisfies(error -> {
                    assertThat(((PlatformException) error).code()).isEqualTo(errorCode);
                    assertThat(((PlatformException) error).httpStatus()).isEqualTo(platformStatus);
                });
        assertThat(count.get()).isEqualTo(expected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void neverRetriesAnErrorAfterStreamingHasStarted(boolean structured) throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            count.incrementAndGet();
            byte[] bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                    + "data: {\"error\":{\"code\":\"upstream_unavailable\"}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var output = new StringBuilder();
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());
        assertThatThrownBy(() -> {
            if (structured) client.stream(route(),
                    new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")), List.of(), null, null),
                    new AiTurnStreamConsumer() {
                        public void onTextDelta(String text) { output.append(text); }
                        public void onComplete(AiTurnResponse result) { throw new AssertionError(); }
                    });
            else client.stream(route(), AiTextRequest.userText("hello"), output::append);
        }).isInstanceOf(PlatformException.class).hasMessageContaining("暂时不可用")
                .satisfies(failure -> assertThat(((PlatformException) failure).code()).isEqualTo("AI_PROVIDER_UNAVAILABLE"));
        assertThat(output.toString()).isEqualTo("partial");
        assertThat(count.get()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void boundsLocalConnectTimeoutRetryAndHonorsInterruption(boolean interrupt) throws Exception {
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenAnswer(invocation -> {
                    if (interrupt) Thread.currentThread().interrupt();
                    throw new java.net.http.HttpConnectTimeoutException("connection timed out");
                });
        var client = new OpenAiCompatibleModelClient(http, new ObjectMapper());
        var route = new ResolvedAiModelRoute("test", AiModelProtocol.OPENAI_COMPATIBLE,
                "http://127.0.0.1:1/v1", "test", "secret");
        try {
            assertThatThrownBy(() -> client.generate(route, AiTextRequest.userText("hello")))
                    .isInstanceOf(PlatformException.class)
                    .hasNoCause()
                    .satisfies(error -> assertThat(((PlatformException) error).code())
                            .isEqualTo(interrupt ? "AI_MODEL_INTERRUPTED" : "AI_MODEL_TIMEOUT"))
                    .hasMessageNotContaining("当前页面");
            org.mockito.Mockito.verify(http, org.mockito.Mockito.times(interrupt ? 1 : 2))
                    .send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                            org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any());
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupt);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sanitizesNetworkFailuresAcrossAllInvocationTransports() throws Exception {
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenThrow(new IOException("private endpoint and credential"));
        var client = new OpenAiCompatibleModelClient(http, new ObjectMapper());
        var route = new ResolvedAiModelRoute("test", AiModelProtocol.OPENAI_COMPATIBLE,
                "http://127.0.0.1:1/v1", "test", "secret");
        AiTurnRequest turn = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")),
                List.of(), null, 512);
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> invocations = List.of(
                () -> client.generate(route, AiTextRequest.userText("hello")),
                () -> client.stream(route, AiTextRequest.userText("hello"), text -> {}),
                () -> client.complete(route, turn),
                () -> client.stream(route, turn, new AiTurnStreamConsumer() {
                    public void onTextDelta(String text) {}
                    public void onComplete(AiTurnResponse response) {}
                }));
        for (var invocation : invocations) {
            assertThatThrownBy(invocation).isInstanceOf(PlatformException.class).hasNoCause()
                    .hasMessageNotContaining("private endpoint")
                    .satisfies(error -> {
                        assertThat(((PlatformException) error).code()).isEqualTo("AI_MODEL_CONNECTION_FAILED");
                        assertThat(((PlatformException) error).httpStatus()).isEqualTo(502);
                    });
        }
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(4)).send(
                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any());
    }

    private OpenAiCompatibleModelClient responseClient(int status, String response) throws IOException {
        return responseClient(status, response, new java.util.concurrent.atomic.AtomicInteger());
    }

    private OpenAiCompatibleModelClient responseClient(int status, String response,
                                                     java.util.concurrent.atomic.AtomicInteger requests) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
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
