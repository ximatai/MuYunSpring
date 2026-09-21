package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelGatewayTest {
    @Test
    void structuredStreamingDefaultsToOneCompletedTurnForExistingGatewayImplementations() {
        AiTurnResponse expected = new AiTurnResponse("ready", java.util.List.of(), "stop", "request-default");
        AiModelGateway gateway = new AiModelGateway() {
            @Override
            public AiTextResponse generate(AiTextRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(AiTextRequest request, AiTextStreamConsumer consumer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResponse complete(AiTurnRequest request) {
                return expected;
            }
        };
        var events = new java.util.ArrayList<String>();

        gateway.stream(new AiTurnRequest(java.util.List.of(
                        new AiChatMessage(AiChatMessage.Role.USER, "describe")), java.util.List.of(), null, null),
                new AiTurnStreamConsumer() {
                    @Override
                    public void onTextDelta(String text) {
                        events.add("text:" + text);
                    }

                    @Override
                    public void onComplete(AiTurnResponse response) {
                        events.add("complete:" + response.requestId());
                    }
                });

        assertThat(events).containsExactly("text:ready", "complete:request-default");
    }

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

    @Test
    void resolvesTheRuntimeRouteForAStructuredTurn() {
        AiModelRouteResolver routes = mock(AiModelRouteResolver.class);
        AiModelClient client = mock(AiModelClient.class);
        ResolvedAiModelRoute route = new ResolvedAiModelRoute("provider",
                AiModelProtocol.OPENAI_COMPATIBLE, "https://example.test/v1", "model", "secret");
        AiTurnRequest request = new AiTurnRequest(java.util.List.of(
                new AiChatMessage(AiChatMessage.Role.USER, "open customers")), java.util.List.of(), null, null);
        AiTurnResponse expected = new AiTurnResponse("done", java.util.List.of(), "stop", "request-1");
        when(routes.resolveCurrent()).thenReturn(route);
        when(client.complete(route, request)).thenReturn(expected);

        AiTurnResponse actual = new DefaultAiModelGateway(routes, client).complete(request);

        assertThat(actual).isSameAs(expected);
        verify(routes).resolveCurrent();
        verify(client).complete(route, request);
    }

    @Test
    void resolvesTheRuntimeRouteForAStreamingStructuredTurn() {
        AiModelRouteResolver routes = mock(AiModelRouteResolver.class);
        AiModelClient client = mock(AiModelClient.class);
        ResolvedAiModelRoute route = new ResolvedAiModelRoute("provider",
                AiModelProtocol.OPENAI_COMPATIBLE, "https://example.test/v1", "model", "secret");
        AiTurnRequest request = new AiTurnRequest(java.util.List.of(
                new AiChatMessage(AiChatMessage.Role.USER, "open customers")), java.util.List.of(), null, null);
        AiTurnStreamConsumer consumer = mock(AiTurnStreamConsumer.class);
        when(routes.resolveCurrent()).thenReturn(route);

        new DefaultAiModelGateway(routes, client).stream(request, consumer);

        verify(routes).resolveCurrent();
        verify(client).stream(route, request, consumer);
    }

    @Test
    void structuredResponseRejectsDuplicateToolCallIds() {
        assertThatThrownBy(() -> new AiTurnResponse(null, java.util.List.of(
                new AiToolCall("call-1", "page.describe", java.util.Map.of()),
                new AiToolCall("call-1", "form.describe", java.util.Map.of())), "tool_calls", "request-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate AI tool call id");
    }
}
