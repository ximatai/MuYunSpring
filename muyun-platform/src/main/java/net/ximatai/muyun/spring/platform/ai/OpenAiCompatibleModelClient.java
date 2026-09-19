package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

/** Minimal OpenAI chat-completions adapter shared by the allowed first-stage providers. */
@Service
final class OpenAiCompatibleModelClient implements AiModelClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_STRUCTURED_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_TOOL_CALLS = 8;
    private static final int MAX_TOOL_ARGUMENT_BYTES = 65_536;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    OpenAiCompatibleModelClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper) {
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                requireSuccess(response.statusCode());
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (consumeStreamEvent(data, consumer)) return;
                        data.setLength(0);
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring("data:".length()).stripLeading());
                    }
                }
                if (consumeStreamEvent(data, consumer)) return;
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
            HttpResponse<InputStream> response = httpClient.send(turnRequest(route, request),
                    HttpResponse.BodyHandlers.ofInputStream());
            final JsonNode root;
            try (InputStream body = response.body()) {
                requireSuccess(response.statusCode());
                root = readResponseObject(readBoundedStructuredBody(body),
                        "AI model returned an invalid structured response");
            }
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            List<AiToolCall> calls = toolCalls(message.path("tool_calls"), request.tools());
            String text = textOrNull(message.path("content"));
            if ((text == null || text.isBlank()) && calls.isEmpty()) {
                throw new PlatformException("AI model structured response contains neither text nor tool calls");
            }
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

    /** Error payloads may contain provider details; never expose them through platform exceptions. */
    private boolean consumeStreamEvent(StringBuilder data, AiTextStreamConsumer consumer) {
        String payload = data.toString().trim();
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
        body.put("messages", request.messages().stream().map(message -> Map.of(
                "role", message.role().name().toLowerCase(java.util.Locale.ROOT), "content", message.content())).toList());
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

    private HttpRequest turnRequest(ResolvedAiModelRoute route, AiTurnRequest request) throws Exception {
        if (route.protocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw new PlatformException("AI model protocol is not supported: " + route.protocol());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.modelId());
        body.put("messages", request.messages().stream().map(message -> Map.of(
                "role", message.role().name().toLowerCase(java.util.Locale.ROOT), "content", message.content())).toList());
        if (!request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (int index = 0; index < request.tools().size(); index++) {
                AiToolDefinition tool = request.tools().get(index);
                tools.add(Map.of("type", "function", "function", Map.of(
                        "name", providerToolName(index),
                        "description", tool.description(),
                        "parameters", providerParameters(tool.inputSchema()))));
            }
            body.put("tools", tools);
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxOutputTokens() != null) body.put("max_tokens", request.maxOutputTokens());
        return HttpRequest.newBuilder(URI.create(route.chatCompletionsUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + route.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
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
            int index = providerToolIndex(name, tools.size());
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

    private String providerToolName(int index) {
        return "capability_" + index;
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

    private int providerToolIndex(String name, int toolCount) {
        if (name == null || !name.startsWith("capability_")) {
            throw new PlatformException("AI model requested an undeclared tool");
        }
        try {
            int index = Integer.parseInt(name.substring("capability_".length()));
            if (index < 0 || index >= toolCount) throw new PlatformException("AI model requested an undeclared tool");
            return index;
        } catch (NumberFormatException exception) {
            throw new PlatformException("AI model requested an undeclared tool");
        }
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
