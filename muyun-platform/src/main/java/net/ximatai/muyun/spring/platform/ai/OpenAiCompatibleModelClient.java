package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);
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
            HttpResponse<InputStream> response = send(request(route, request, false));
            final JsonNode root;
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                root = readResponseObject(readBoundedStructuredBody(body), "AI model returned an invalid response", response.statusCode());
            }
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText(null);
            if (text == null) throw new PlatformException("AI model response does not contain text");
            log.info("AI text response completed finishReason={} textCharacters={}",
                    safeFinishReason(textOrNull(choice.path("finish_reason"))), text.length());
            return new AiTextResponse(text, textOrNull(choice.path("finish_reason")), response.headers()
                    .firstValue("x-request-id").orElse(null), usage(root));
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI_MODEL_INTERRUPTED", 503, "模型请求已中断。");
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public void stream(ResolvedAiModelRoute route, AiTextRequest request, AiTextStreamConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        try {
            HttpResponse<InputStream> response = send(request(route, request, true));
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                if (consumeSseStream(body, payload -> consumeStreamEvent(payload, consumer, response.statusCode()))) return;
                throw new PlatformException("AI_MODEL_INCOMPLETE_RESPONSE", 502, "模型回复在完成前断开。");
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI_MODEL_INTERRUPTED", 503, "模型请求已中断。");
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public AiTurnResponse complete(ResolvedAiModelRoute route, AiTurnRequest request) {
        try {
            HttpResponse<InputStream> response = send(turnRequest(route, request, false));
            final JsonNode root;
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                root = readResponseObject(readBoundedStructuredBody(body),
                        "AI model returned an invalid structured response", response.statusCode());
            }
            AiTokenUsage reportedUsage = usage(root);
            try {
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty() || !choices.path(0).isObject()
                        || !choices.path(0).path("message").isObject()) {
                    throw new PlatformException("AI model returned an invalid structured response");
                }
                JsonNode choice = choices.path(0);
                JsonNode message = choice.path("message");
                List<AiToolCall> calls = toolCalls(message.path("tool_calls"), request.tools(), request.indexedToolCodes());
                String text = textOrNull(message.path("content"));
                return new AiTurnResponse(text, calls, textOrNull(choice.path("finish_reason")),
                        response.headers().firstValue("x-request-id").orElse(null), reportedUsage);
            } catch (PlatformException | IllegalArgumentException failure) {
                var calls = root.path("choices").path(0).path("message").path("tool_calls");
                throw observedResponseFailure(failure, reportedUsage, calls.isArray() ? calls.size() : null);
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI_MODEL_INTERRUPTED", 503, "模型请求已中断。");
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public void stream(ResolvedAiModelRoute route, AiTurnRequest request, AiTurnStreamConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        try {
            HttpResponse<InputStream> response = send(turnRequest(route, request, true));
            try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                StructuredTurnAccumulator accumulator = new StructuredTurnAccumulator(request.tools(), request.indexedToolCodes(), consumer,
                        response.headers().firstValue("x-request-id").orElse(null));
                if (consumeSseStream(body, payload -> consumeStructuredStreamEvent(payload, accumulator, response.statusCode()))) return;
                throw new PlatformException("AI_MODEL_INCOMPLETE_RESPONSE", 502, "模型回复在完成前断开。");
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("AI_MODEL_INTERRUPTED", 503, "模型请求已中断。");
        } catch (Exception exception) {
            throw transportFailure(exception);
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
            if (expired) throw new PlatformException("AI_MODEL_TIMEOUT", 504, "等待模型回复超时，请稍后重试。");
        }

        @Override
        public void close() throws IOException {
            deadline.interrupt();
            super.close();
        }
    }

    /** Error payloads may contain provider details; never expose them through platform exceptions. */
    private boolean consumeStreamEvent(String value, AiTextStreamConsumer consumer, int httpStatus) {
        String payload = value.trim();
        if (payload.isEmpty()) return false;
        if ("[DONE]".equals(payload)) return true;
        JsonNode event = readResponseObject(payload, "AI model stream contains an invalid event", httpStatus);
        usage(event);
        JsonNode delta = event.path("choices").path(0).path("delta").path("content");
        if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
            consumer.accept(delta.asText());
        }
        return false;
    }

    /** Conservative UTF-8 estimate, not a provider tokenizer. Never trim business content here. */
    private void checkContextBudget(ResolvedAiModelRoute route, Map<String, Object> body) throws IOException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", body.get("messages"));
        if (body.containsKey("tools")) input.put("tools", body.get("tools"));
        long estimatedInput = objectMapper.writeValueAsBytes(input).length + 256L;
        int output = (Integer) body.get("max_tokens");
        Integer capacity = route.limits().contextWindowTokens();
        long reserve = capacity == null ? 0 : Math.max(256, capacity / 20);
        log.info("AI invocation budget contextCapacity={} estimatedInputTokens={} outputBudget={} reserveTokens={} estimator=utf8-conservative",
                capacity, estimatedInput, output, reserve);
        if (capacity != null && estimatedInput + output + reserve > capacity) {
            throw new PlatformException("AI_CONTEXT_BUDGET_EXCEEDED", 422,
                    "本次内容预计超过模型上下文预算，尚未发送给模型。请缩小本次处理范围，或核对智能模型配置中的容量与输出预算");
        }
    }

    private AiTokenUsage usage(JsonNode root) {
        JsonNode value = root.path("usage");
        if (!value.isObject()) return null;
        Long input = tokenCount(value.path("prompt_tokens"));
        Long output = tokenCount(value.path("completion_tokens"));
        Long total = tokenCount(value.path("total_tokens"));
        if (input == null && output == null && total == null) return null;
        log.info("AI provider usage inputTokens={} outputTokens={} totalTokens={}", input, output, total);
        return new AiTokenUsage(input, output, total);
    }

    private Long tokenCount(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null;
    }

    private JsonNode readResponseObject(String payload, String invalidMessage, int httpStatus) {
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
            log.warn("AI provider rejected response transport=body httpStatus={} diagnostics={}",
                    httpStatus, providerErrorDiagnostics(response.get("error")));
            if ("upstream_unavailable".equals(response.path("error").path("code").asText())) {
                throw new PlatformException("AI_PROVIDER_UNAVAILABLE", 503, "模型服务暂时不可用，请稍后重试。");
            }
            throw new PlatformException("AI_PROVIDER_REQUEST_REJECTED", 502, "模型服务拒绝了本次请求，请联系管理员检查模型配置与服务状态。");
        }
        return response;
    }

    /** Only protocol allowlists enter logs; provider messages, unknown tokens and causes stay private. */
    static Map<String, Object> providerErrorDiagnostics(JsonNode error) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("shape", error.isObject() ? "object" : "invalid");
        diagnostics.put("code", safeProviderErrorValue(error.path("code"), List.of(
                "upstream_unavailable", "invalid_api_key", "rate_limit_exceeded", "insufficient_quota",
                "content_filter", "content_policy_violation", "context_length_exceeded")));
        diagnostics.put("type", safeProviderErrorValue(error.path("type"), List.of(
                "authentication_error", "rate_limit_error", "server_error", "invalid_request_error")));
        JsonNode status = error.path("status");
        diagnostics.put("reportedStatus", status.isMissingNode() || status.isNull() ? "missing"
                : status.isIntegralNumber() && status.canConvertToInt() && status.intValue() >= 100
                && status.intValue() <= 599 ? status.intValue() : "invalid");
        return diagnostics;
    }

    private static String safeProviderErrorValue(JsonNode value, List<String> allowed) {
        if (value.isMissingNode() || value.isNull()) return "missing";
        if (!value.isTextual()) return "invalid";
        // Return the platform's literal, never the provider's string.
        return allowed.stream().filter(known -> known.equals(value.textValue())).findFirst().orElse("other");
    }

    private HttpRequest request(ResolvedAiModelRoute route, AiTextRequest request, boolean stream) throws Exception {
        if (route.protocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw new PlatformException("AI model protocol is not supported: " + route.protocol());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.modelId());
        body.put("messages", wireMessages(request.messages()));
        if (route.reasoningEffort() != null) body.put("reasoning_effort", route.reasoningEffort().getCode());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("max_tokens", route.limits().outputBudget(request.maxOutputTokens()));
        if (stream) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        checkContextBudget(route, body);
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
        } else {
            body.put("tool_choice", "none");
        }
        if (route.reasoningEffort() != null) body.put("reasoning_effort", route.reasoningEffort().getCode());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("max_tokens", route.limits().outputBudget(request.maxOutputTokens()));
        if (stream) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        checkContextBudget(route, body);
        return HttpRequest.newBuilder(URI.create(route.chatCompletionsUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + route.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    private boolean consumeStructuredStreamEvent(String value, StructuredTurnAccumulator accumulator, int httpStatus) {
        String payload = value.trim();
        if (payload.isEmpty()) return false;
        if ("[DONE]".equals(payload)) {
            accumulator.complete();
            return true;
        }
        accumulator.accept(readResponseObject(payload, "AI model structured stream contains an invalid event", httpStatus));
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
        private final List<String> indexedToolCodes;
        private final AiTurnStreamConsumer consumer;
        private final String requestId;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, StructuredToolCallAccumulator> calls = new LinkedHashMap<>();
        private String finishReason;
        private AiTokenUsage usage;
        private boolean sawChoice;
        private int accumulatedBytes;

        private StructuredTurnAccumulator(List<AiToolDefinition> tools, List<String> indexedToolCodes, AiTurnStreamConsumer consumer,
                                          String requestId) {
            this.tools = tools;
            this.indexedToolCodes = indexedToolCodes;
            this.consumer = consumer;
            this.requestId = requestId;
        }

        private void accept(JsonNode root) {
            AiTokenUsage reported = usage(root);
            if (reported != null) usage = reported;
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
            AiTurnResponse response;
            try {
                var toolCalls = calls.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getValue().toToolCall(tools, indexedToolCodes))
                        .toList();
                response = new AiTurnResponse(text.isEmpty() ? null : text.toString(), toolCalls,
                        finishReason, requestId, usage);
            } catch (PlatformException | IllegalArgumentException failure) {
                throw observedResponseFailure(failure, usage, calls.size());
            }
            log.info("AI structured stream completed finishReason={} textCharacters={} toolCallCount={} outputBytes={}",
                    safeFinishReason(finishReason), text.length(), response.toolCalls().size(), accumulatedBytes);
            consumer.onComplete(response);
        }
    }

    private static AiModelResponseException observedResponseFailure(RuntimeException failure, AiTokenUsage usage, Integer count) {
        // Provider ids and malformed values are never included in the public rejection.
        PlatformException rejection = failure instanceof PlatformException platformFailure ? platformFailure
                : new PlatformException("AI model returned invalid tool calls");
        return new AiModelResponseException(rejection, usage, count);
    }

    private final class StructuredToolCallAccumulator {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private AiToolCall toToolCall(List<AiToolDefinition> tools, List<String> indexedToolCodes) {
            if (id.isEmpty() || name.isEmpty() || arguments.isEmpty()) {
                throw new PlatformException("AI model returned invalid tool calls");
            }
            int toolIndex = providerToolIndex(name.toString(), tools, indexedToolCodes);
            JsonNode parsed = readJsonObject(arguments.toString(), "AI model returned invalid tool arguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> values = objectMapper.convertValue(parsed, Map.class);
            return new AiToolCall(id.toString(), tools.get(toolIndex).code(), values);
        }
    }

    private List<AiToolCall> toolCalls(JsonNode nodes, List<AiToolDefinition> tools, List<String> indexedToolCodes) {
        if (nodes == null || nodes.isMissingNode() || nodes.isNull()) return List.of();
        if (!nodes.isArray()) throw new PlatformException("AI model returned invalid tool calls");
        if (nodes.size() > MAX_TOOL_CALLS) throw new PlatformException("AI model returned too many tool calls");
        List<AiToolCall> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String id = textOrNull(node.path("id"));
            JsonNode function = node.path("function");
            String name = textOrNull(function.path("name"));
            int index = providerToolIndex(name, tools, indexedToolCodes);
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
        // Dots are the only unsupported characters in ordinary capability codes.
        // Keep their wire names readable and injective without making the model copy a digest.
        String readable = code.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (code.matches("[a-zA-Z0-9.-]+") && readable.length() <= 60) {
            return "cap_" + readable;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8));
            // A separate namespace also prevents fallback names from colliding with short codes.
            if (readable.length() > 46) readable = readable.substring(0, 46);
            return "capx_" + readable + "_" + java.util.HexFormat.of().formatHex(digest).substring(0, 12);
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

    private int providerToolIndex(String name, List<AiToolDefinition> tools, List<String> indexedToolCodes) {
        for (int index = 0; index < tools.size(); index++) {
            if (providerToolName(tools.get(index).code()).equals(name)) return index;
        }
        List<String> missing = indexedToolCodes.stream().filter(code -> providerToolName(code).equals(name)).limit(1).toList();
        // Only a known catalog code may cross the error boundary; never forward raw model names or arguments.
        throw new PlatformException("AI_MODEL_UNDECLARED_TOOL", 502, "AI model requested an undeclared tool",
                ErrorScope.empty(), List.of(), missing.isEmpty() ? Map.of() : Map.of("missingToolCodes", missing));
    }

    /** Retry only before accepting a successful response; never replay a partially consumed stream. */
    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            String reason;
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response;
                log.warn("AI provider rejected response transport=http status={} attempt={}", status, attempt + 1);
                boolean retryable = false;
                try (InputStream body = new TimedResponseBody(response.body(), bodyTimeout)) {
                    // Only a structured upstream failure qualifies; never infer retryability from free text.
                    if (attempt == 0 && (status == 500 || status == 502 || status == 503 || status == 504)) {
                        byte[] bytes = body.readNBytes(8193);
                        if (bytes.length <= 8192) {
                            try {
                                JsonNode error = objectMapper.readTree(bytes);
                                retryable = error != null && "upstream_unavailable".equals(error.path("error").path("code").asText());
                            } catch (IOException ignored) {
                                // Malformed or non-JSON errors remain ordinary provider failures.
                            }
                        }
                    }
                }
                if (!retryable) throw providerHttpFailure(status);
                reason = "upstream_unavailable";
            } catch (java.net.http.HttpConnectTimeoutException exception) {
                if (attempt != 0) throw exception;
                reason = "connect-timeout";
            }
            log.info("AI model connection retry attempt=1 maxRetries=1 reason={}", reason);
            Thread.sleep(500);
        }
    }

    private static PlatformException providerHttpFailure(int status) {
        // Provider authentication failures belong to the model connection, not the user's login session.
        String suffix = "（HTTP " + status + "）";
        if (status == 401 || status == 403) {
            return new PlatformException("AI_PROVIDER_AUTHENTICATION_FAILED", 502,
                    "模型连接鉴权失败" + suffix + "，请联系管理员检查凭据及模型访问权限。");
        }
        if (status == 429) {
            return new PlatformException("AI_PROVIDER_RATE_LIMITED", 429,
                    "模型服务限制了本次请求" + suffix + "，请稍后重试；持续失败时请管理员检查额度。");
        }
        if (status >= 500) {
            return new PlatformException("AI_PROVIDER_UNAVAILABLE", 503,
                    "模型服务暂时不可用" + suffix + "，请稍后重试。");
        }
        return new PlatformException("AI_PROVIDER_REQUEST_REJECTED", 502,
                "模型服务拒绝了本次请求" + suffix + "，请联系管理员检查模型配置与服务状态。");
    }

    private static PlatformException transportFailure(Exception failure) {
        // Network exceptions may include endpoints or credentials; retain only their category.
        if (failure instanceof java.net.http.HttpTimeoutException) {
            return new PlatformException("AI_MODEL_TIMEOUT", 504, "等待模型回复超时，请稍后重试。");
        }
        if (failure instanceof IOException) {
            return new PlatformException("AI_MODEL_CONNECTION_FAILED", 502,
                    "模型连接失败，请联系管理员检查网络与模型服务状态。");
        }
        return new PlatformException("AI_MODEL_CALL_FAILED", 502,
                "模型调用未能完成，请联系管理员检查模型服务状态。");
    }

    static String safeFinishReason(String reason) {
        if (reason == null) return "missing";
        return switch (reason) {
            case "stop", "tool_calls", "length", "content_filter" -> reason;
            default -> "other";
        };
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
