package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Minimal OpenAI chat-completions adapter shared by the allowed first-stage providers. */
@Service
final class OpenAiCompatibleModelClient implements AiModelClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_STRUCTURED_RESPONSE_BYTES = 1_048_576;
    // SSE framing and provider metadata are bounded separately from accumulated output.
    private static final int MAX_STREAM_TRANSPORT_BYTES = 16 * MAX_STRUCTURED_RESPONSE_BYTES;
    private static final int MAX_STRUCTURED_EVENT_BYTES = 131_072;
    private static final int MAX_TOOL_CALLS = 8;
    private static final int MAX_TOOL_ARGUMENT_BYTES = 65_536;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration bodyTimeout;

    @Autowired
    OpenAiCompatibleModelClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, REQUEST_TIMEOUT);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper, Duration bodyTimeout) {
        if (bodyTimeout.isNegative() || bodyTimeout.isZero()) throw new IllegalArgumentException("AI body timeout must be positive");
        this.bodyTimeout = bodyTimeout;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public AiTextResponse generate(ResolvedAiModelRoute route, AiTextRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request(route, request, false),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(response.statusCode());
            JsonNode root = readResponseObject(response.body(), "AI model returned an invalid response");
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText(null);
            if (text == null) throw new PlatformException("AI model response does not contain text");
            return new AiTextResponse(text, textOrNull(choice.path("finish_reason")), response.headers()
                    .firstValue("x-request-id").orElse(null));
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI model request interrupted", exception);
        } catch (Exception exception) {
            throw new PlatformException("AI model request failed", exception);
        }
    }

    @Override
    public void stream(ResolvedAiModelRoute route, AiTextRequest request, AiTextStreamConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        try {
            HttpResponse<InputStream> response = httpClient.send(request(route, request, true),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                requireSuccess(response.statusCode());
                if (consumeSseStream(body, payload -> consumeStreamEvent(payload, consumer))) return;
                throw new PlatformException("AI model stream ended before completion");
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI model streaming request interrupted", exception);
        } catch (Exception exception) {
            throw new PlatformException("AI model streaming request failed", exception);
        }
    }

    @Override
    public AiTurnResponse complete(ResolvedAiModelRoute route, AiTurnRequest request) {
        try {
            HttpResponse<InputStream> response = httpClient.send(turnRequest(route, request, false),
                    HttpResponse.BodyHandlers.ofInputStream());
            final JsonNode root;
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                requireSuccess(response.statusCode());
                root = readResponseObject(readBoundedStructuredBody(body),
                        "AI model returned an invalid structured response");
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty() || !choices.path(0).isObject()
                    || !choices.path(0).path("message").isObject()) {
                throw new PlatformException("AI model returned an invalid structured response");
            }
            JsonNode choice = choices.path(0);
            JsonNode message = choice.path("message");
            List<AiToolCall> calls = toolCalls(message.path("tool_calls"), request.tools());
            String text = textOrNull(message.path("content"));
            return new AiTurnResponse(text, calls, textOrNull(choice.path("finish_reason")),
                    response.headers().firstValue("x-request-id").orElse(null));
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI model structured request interrupted", exception);
        } catch (Exception exception) {
            throw new PlatformException("AI model structured request failed", exception);
        }
    }

    @Override
    public void stream(ResolvedAiModelRoute route, AiTurnRequest request, AiTurnStreamConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        try {
            HttpResponse<InputStream> response = httpClient.send(turnRequest(route, request, true),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                requireSuccess(response.statusCode());
                StructuredTurnAccumulator accumulator = new StructuredTurnAccumulator(request.tools(), consumer,
                        response.headers().firstValue("x-request-id").orElse(null));
                if (consumeSseStream(body, payload -> consumeStructuredStreamEvent(payload, accumulator))) return;
                throw new PlatformException("AI model structured stream ended before completion");
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI model structured streaming request interrupted", exception);
        } catch (Exception exception) {
            throw new PlatformException("AI model structured streaming request failed", exception);
        }
    }

    /** HttpRequest.timeout ends at headers with ofInputStream; bound the remaining body lifetime too. */
    private static final class TimedResponseBody extends FilterInputStream {
        private volatile boolean expired;
        private final Thread deadline;

        TimedResponseBody(InputStream body, Duration timeout) {
            super(body);
            deadline = Thread.ofVirtual().name("ai-response-deadline").start(() -> {
                try {
                    Thread.sleep(timeout);
                    expired = true;
                    body.close();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Closing is best effort; readers still check the deadline before exposing content.
                }
            });
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            try { return in.read(); } finally { checkDeadline(); }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkDeadline();
            try { return in.read(bytes, offset, length); } finally { checkDeadline(); }
        }

        private void checkDeadline() {
            if (expired) throw new PlatformException("AI model response body timed out");
        }

        @Override
        public void close() throws IOException {
            deadline.interrupt();
            super.close();
        }
    }

    /** Error payloads may contain provider details; never expose them through platform exceptions. */
    private boolean consumeStreamEvent(String value, AiTextStreamConsumer consumer) {
        String payload = value.trim();
        if (payload.isEmpty()) return false;
        if ("[DONE]".equals(payload)) return true;
        JsonNode event = readResponseObject(payload, "AI model stream contains an invalid event");
        JsonNode delta = event.path("choices").path(0).path("delta").path("content");
        if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
            consumer.accept(delta.asText());
        }
        return false;
    }

    private JsonNode readResponseObject(String payload, String invalidMessage) {
        final JsonNode response;
        try {
            response = objectMapper.readTree(payload);
        } catch (IOException exception) {
            // Jackson diagnostics can include response content; keep it out of exception chains.
            throw new PlatformException(invalidMessage);
        }
        if (response == null || !response.isObject()) {
            throw new PlatformException(invalidMessage);
        }
        if (response.hasNonNull("error")) {
            throw new PlatformException("AI model request was rejected by provider");
        }
        return response;
    }

    private HttpRequest request(ResolvedAiModelRoute route, AiTextRequest request, boolean stream) throws Exception {
        if (route.protocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw new PlatformException("AI model protocol is not supported: " + route.protocol());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.modelId());
        body.put("messages", wireMessages(request.messages()));
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxOutputTokens() != null) body.put("max_tokens", request.maxOutputTokens());
        if (stream) body.put("stream", true);
        return HttpRequest.newBuilder(URI.create(route.chatCompletionsUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + route.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest turnRequest(ResolvedAiModelRoute route, AiTurnRequest request, boolean stream) throws Exception {
        if (route.protocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw new PlatformException("AI model protocol is not supported: " + route.protocol());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.modelId());
        body.put("messages", wireMessages(request.messages()));
        if (!request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (int index = 0; index < request.tools().size(); index++) {
                AiToolDefinition tool = request.tools().get(index);
                tools.add(Map.of("type", "function", "function", Map.of(
                        "name", providerToolName(tool.code()),
                        "description", tool.description(),
                        "parameters", providerParameters(tool.inputSchema()))));
            }
            body.put("tools", tools);
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxOutputTokens() != null) body.put("max_tokens", request.maxOutputTokens());
        if (stream) body.put("stream", true);
        return HttpRequest.newBuilder(URI.create(route.chatCompletionsUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + route.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    private boolean consumeStructuredStreamEvent(String value, StructuredTurnAccumulator accumulator) {
        String payload = value.trim();
        if (payload.isEmpty()) return false;
        if ("[DONE]".equals(payload)) {
            accumulator.complete();
            return true;
        }
        accumulator.accept(readResponseObject(payload, "AI model structured stream contains an invalid event"));
        return false;
    }

    /** Reads provider SSE framing with bounded allocation before handing payloads to protocol-specific consumers. */
    private boolean consumeSseStream(InputStream input, Predicate<String> eventConsumer) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuilder data = new StringBuilder();
        int totalBytes = 0;
        int next;
        while ((next = input.read()) != -1) {
            if (++totalBytes > MAX_STREAM_TRANSPORT_BYTES) {
                throw new PlatformException("AI model returned an oversized structured response");
            }
            if (next == '\n') {
                if (consumeSseLine(line, data, eventConsumer)) return true;
                line.reset();
            } else if (next != '\r') {
                if (line.size() >= MAX_STRUCTURED_EVENT_BYTES) {
                    throw new PlatformException("AI model stream contains an oversized event");
                }
                line.write(next);
            }
        }
        if (line.size() > 0 && consumeSseLine(line, data, eventConsumer)) return true;
        return eventConsumer.test(data.toString());
    }

    private boolean consumeSseLine(ByteArrayOutputStream line, StringBuilder data,
                                   Predicate<String> eventConsumer) {
        if (line.size() == 0) {
            boolean complete = eventConsumer.test(data.toString());
            data.setLength(0);
            return complete;
        }
        String value = line.toString(StandardCharsets.UTF_8);
        if (!value.startsWith("data:")) return false;
        if (!data.isEmpty()) data.append('\n');
        data.append(value.substring("data:".length()).stripLeading());
        if (data.toString().getBytes(StandardCharsets.UTF_8).length > MAX_STRUCTURED_EVENT_BYTES) {
            throw new PlatformException("AI model stream contains an oversized event");
        }
        return false;
    }

    private final class StructuredTurnAccumulator {
        private final List<AiToolDefinition> tools;
        private final AiTurnStreamConsumer consumer;
        private final String requestId;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, StructuredToolCallAccumulator> calls = new LinkedHashMap<>();
        private String finishReason;
        private boolean sawChoice;
        private int accumulatedBytes;

        private StructuredTurnAccumulator(List<AiToolDefinition> tools, AiTurnStreamConsumer consumer,
                                          String requestId) {
            this.tools = tools;
            this.consumer = consumer;
            this.requestId = requestId;
        }

        private void accept(JsonNode root) {
            JsonNode choices = root.path("choices");
            if (!choices.isArray()) {
                throw new PlatformException("AI model structured stream contains an invalid event");
            }
            if (choices.isEmpty()) return;
            JsonNode choice = choices.path(0);
            if (!choice.isObject() || !choice.path("delta").isObject()) {
                throw new PlatformException("AI model structured stream contains an invalid event");
            }
            sawChoice = true;
            JsonNode delta = choice.path("delta");
            String content = textOrNull(delta.path("content"));
            if (content != null) {
                text.append(content);
                addAccumulatedBytes(content);
                consumer.onTextDelta(content);
            }
            accumulateToolCalls(delta.path("tool_calls"));
            String currentFinishReason = textOrNull(choice.path("finish_reason"));
            if (currentFinishReason != null) finishReason = currentFinishReason;
        }

        private void accumulateToolCalls(JsonNode nodes) {
            if (nodes.isMissingNode() || nodes.isNull()) return;
            if (!nodes.isArray()) throw new PlatformException("AI model returned invalid tool calls");
            for (JsonNode node : nodes) {
                int index = node.path("index").asInt(-1);
                if (index < 0 || index >= MAX_TOOL_CALLS) {
                    throw new PlatformException("AI model returned too many tool calls");
                }
                StructuredToolCallAccumulator call = calls.computeIfAbsent(index,
                        ignored -> new StructuredToolCallAccumulator());
                String id = textOrNull(node.path("id"));
                if (id != null) {
                    call.id.append(id);
                    addAccumulatedBytes(id);
                }
                JsonNode function = node.path("function");
                if (!function.isMissingNode() && !function.isNull()) {
                    if (!function.isObject()) throw new PlatformException("AI model returned invalid tool calls");
                    String name = textOrNull(function.path("name"));
                    String arguments = textOrNull(function.path("arguments"));
                    if (name != null) {
                        call.name.append(name);
                        addAccumulatedBytes(name);
                    }
                    if (arguments != null) {
                        call.arguments.append(arguments);
                        addAccumulatedBytes(arguments);
                    }
                }
                if (call.arguments.toString().getBytes(StandardCharsets.UTF_8).length > MAX_TOOL_ARGUMENT_BYTES) {
                    throw new PlatformException("AI model returned oversized tool arguments");
                }
            }
        }

        private void addAccumulatedBytes(String fragment) {
            accumulatedBytes += fragment.getBytes(StandardCharsets.UTF_8).length;
            if (accumulatedBytes > MAX_STRUCTURED_RESPONSE_BYTES) {
                throw new PlatformException("AI model returned an oversized structured response");
            }
        }

        private void complete() {
            if (!sawChoice) {
                throw new PlatformException("AI model returned an invalid structured response");
            }
            List<AiToolCall> toolCalls = calls.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getValue().toToolCall(tools))
                    .toList();
            consumer.onComplete(new AiTurnResponse(text.isEmpty() ? null : text.toString(), toolCalls,
                    finishReason, requestId));
        }
    }

    private final class StructuredToolCallAccumulator {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private AiToolCall toToolCall(List<AiToolDefinition> tools) {
            if (id.isEmpty() || name.isEmpty() || arguments.isEmpty()) {
                throw new PlatformException("AI model returned invalid tool calls");
            }
            int toolIndex = providerToolIndex(name.toString(), tools);
            JsonNode parsed = readJsonObject(arguments.toString(), "AI model returned invalid tool arguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> values = objectMapper.convertValue(parsed, Map.class);
            return new AiToolCall(id.toString(), tools.get(toolIndex).code(), values);
        }
    }

    private List<AiToolCall> toolCalls(JsonNode nodes, List<AiToolDefinition> tools) {
        if (nodes == null || nodes.isMissingNode() || nodes.isNull()) return List.of();
        if (!nodes.isArray()) throw new PlatformException("AI model returned invalid tool calls");
        if (nodes.size() > MAX_TOOL_CALLS) throw new PlatformException("AI model returned too many tool calls");
        List<AiToolCall> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String id = textOrNull(node.path("id"));
            JsonNode function = node.path("function");
            String name = textOrNull(function.path("name"));
            int index = providerToolIndex(name, tools);
            String argumentsJson = textOrNull(function.path("arguments"));
            if (id == null || argumentsJson == null) throw new PlatformException("AI model returned invalid tool calls");
            if (argumentsJson.getBytes(StandardCharsets.UTF_8).length > MAX_TOOL_ARGUMENT_BYTES) {
                throw new PlatformException("AI model returned oversized tool arguments");
            }
            JsonNode arguments = readJsonObject(argumentsJson, "AI model returned invalid tool arguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> values = objectMapper.convertValue(arguments, Map.class);
            result.add(new AiToolCall(id, tools.get(index).code(), values));
        }
        return List.copyOf(result);
    }

    private String readBoundedStructuredBody(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_STRUCTURED_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_STRUCTURED_RESPONSE_BYTES) {
            throw new PlatformException("AI model returned an oversized structured response");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String providerToolName(String code) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8));
            String readable = code.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (readable.length() > 47) readable = readable.substring(0, 47);
            return "cap_" + readable + "_" + java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private List<Map<String, Object>> wireMessages(List<AiChatMessage> messages) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiChatMessage message : messages) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
            if (message.content() != null) wire.put("content", message.content());
            if (message.toolCallId() != null) wire.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (AiToolCall call : message.toolCalls()) {
                    calls.add(Map.of("id", call.id(), "type", "function", "function", Map.of(
                            "name", providerToolName(call.code()),
                            "arguments", objectMapper.writeValueAsString(call.arguments()))));
                }
                wire.put("tool_calls", calls);
            }
            result.add(wire);
        }
        return result;
    }

    /**
     * Some OpenAI-compatible runtimes require object schemas to declare an
     * explicit properties object even though JSON Schema itself does not.
     */
    private Map<String, Object> providerParameters(Map<String, Object> schema) {
        Map<String, Object> normalized = new LinkedHashMap<>(schema);
        if ("object".equals(normalized.get("type")) && !normalized.containsKey("properties")) {
            normalized.put("properties", Map.of());
        }
        return normalized;
    }

    private JsonNode readJsonObject(String payload, String invalidMessage) {
        try {
            JsonNode value = objectMapper.readTree(payload);
            if (value != null && value.isObject()) return value;
        } catch (IOException ignored) {
            // Provider arguments are untrusted; diagnostics may contain their content.
        }
        throw new PlatformException(invalidMessage);
    }

    private int providerToolIndex(String name, List<AiToolDefinition> tools) {
        for (int index = 0; index < tools.size(); index++) {
            if (providerToolName(tools.get(index).code()).equals(name)) return index;
        }
        throw new PlatformException("AI model requested an undeclared tool");
    }

    private void requireSuccess(int statusCode) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new PlatformException("AI model request was rejected with HTTP status " + statusCode);
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
