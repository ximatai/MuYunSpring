package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(new ObjectMapper(),
                ignored -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        AiModelConfiguration configuration = configuration();
        AiTextRequest request = AiTextRequest.userText("hello");

        AiTextResponse response = client.generate(configuration, request);
        StringBuilder streamed = new StringBuilder();
        client.stream(configuration, request, streamed::append);

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(streamed).hasToString("hello");
        assertThat(authorization).containsOnly("Bearer model-secret");
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

    private AiModelConfiguration configuration() {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProvider.LM_STUDIO);
        configuration.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        configuration.setModelId("local-model");
        configuration.setApiKey("model-secret");
        return configuration;
    }
}
