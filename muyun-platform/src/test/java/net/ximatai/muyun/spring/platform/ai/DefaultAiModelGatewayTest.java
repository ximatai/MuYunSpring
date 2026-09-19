package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelGatewayTest {
    @Test
    void resolvesTheRuntimeRouteForEachInvocation() {
        AiModelRouteResolver routes = mock(AiModelRouteResolver.class);
        AiModelClient client = mock(AiModelClient.class);
        ResolvedAiModelRoute route = new ResolvedAiModelRoute("provider",
                AiModelProtocol.OPENAI_COMPATIBLE, "https://example.test/v1", "model", "secret");
        AiTextRequest request = AiTextRequest.userText("hello");
        AiTextResponse expected = new AiTextResponse("world", "stop", "request-1");
        when(routes.resolveCurrent()).thenReturn(route);
        when(client.generate(route, request)).thenReturn(expected);

        DefaultAiModelGateway gateway = new DefaultAiModelGateway(routes, client);
        AiTextResponse first = gateway.generate(request);
        AiTextResponse second = gateway.generate(request);

        assertThat(first).isSameAs(expected);
        assertThat(second).isSameAs(expected);
        verify(routes, times(2)).resolveCurrent();
        verify(client, times(2)).generate(route, request);
    }

    @Test
    void resolvesTheRuntimeRouteForAStreamingInvocation() {
        AiModelRouteResolver routes = mock(AiModelRouteResolver.class);
        AiModelClient client = mock(AiModelClient.class);
        ResolvedAiModelRoute route = new ResolvedAiModelRoute("provider",
                AiModelProtocol.OPENAI_COMPATIBLE, "https://example.test/v1", "model", "secret");
        AiTextRequest request = AiTextRequest.userText("hello");
        AiTextStreamConsumer consumer = mock(AiTextStreamConsumer.class);
        when(routes.resolveCurrent()).thenReturn(route);

        new DefaultAiModelGateway(routes, client).stream(request, consumer);

        verify(routes).resolveCurrent();
        verify(client).stream(route, request, consumer);
    }
}
