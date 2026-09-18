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
import java.util.Map;
import java.util.Objects;

/** Minimal OpenAI chat-completions adapter shared by the allowed first-stage providers. */
@Service
public class OpenAiCompatibleModelClient implements AiModelClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiModelEndpointResolver endpointResolver;

    @Autowired
    public OpenAiCompatibleModelClient(ObjectMapper objectMapper, AiModelEndpointResolver endpointResolver) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper, endpointResolver);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper, AiModelEndpointResolver endpointResolver) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver must not be null");
    }

    @Override
    public AiTextResponse generate(AiModelConfiguration configuration, AiTextRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request(configuration, request, false),
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
    public void stream(AiModelConfiguration configuration, AiTextRequest request, AiTextStreamConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        try {
            HttpResponse<InputStream> response = httpClient.send(request(configuration, request, true),
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

    private HttpRequest request(AiModelConfiguration configuration, AiTextRequest request, boolean stream) throws Exception {
        if (configuration == null || configuration.getProvider() == null || configuration.getProvider().isBlank()) {
            throw new PlatformException("AI model configuration is invalid");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuration.getModelId());
        body.put("messages", request.messages().stream().map(message -> Map.of(
                "role", message.role().name().toLowerCase(java.util.Locale.ROOT), "content", message.content())).toList());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxOutputTokens() != null) body.put("max_tokens", request.maxOutputTokens());
        if (stream) body.put("stream", true);
        return HttpRequest.newBuilder(URI.create(endpointResolver.resolve(configuration) + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configuration.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
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
