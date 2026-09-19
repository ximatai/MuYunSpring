package net.ximatai.muyun.spring.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.ai.AiModelGateway;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiTurnRequest;
import net.ximatai.muyun.spring.platform.ai.AiTurnResponse;
import net.ximatai.muyun.spring.platform.ai.AiTurnStreamConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AssistantTurnServiceTest {
    @Test
    void validatesTheTerminalStructuredResponseBeforeCompletingAStream() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        doAnswer(invocation -> {
            AiTurnStreamConsumer consumer = invocation.getArgument(1);
            consumer.onTextDelta("ready");
            consumer.onComplete(new AiTurnResponse("ready", List.of(), "stop", "request-stream"));
            return null;
        }).when(gateway).stream(org.mockito.ArgumentMatchers.any(AiTurnRequest.class),
                org.mockito.ArgumentMatchers.any(AiTurnStreamConsumer.class));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        List<String> events = new java.util.ArrayList<>();

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.stream(new AssistantTurnCommand("describe", Map.of(), List.of(), List.of()),
                    new AiTurnStreamConsumer() {
                        @Override
                        public void onTextDelta(String text) {
                            events.add("delta:" + text);
                        }

                        @Override
                        public void onComplete(AiTurnResponse response) {
                            events.add("complete:" + response.requestId());
                        }
                    });
        }

        assertThat(events).containsExactly("delta:ready", "complete:request-stream");
    }

    @Test
    void rejectsTruncatedTerminalResponsesForBothSynchronousAndStreamingTurns() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AiTurnResponse truncated = new AiTurnResponse("partial", List.of(), "length", "request-truncated");
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(truncated);
        doAnswer(invocation -> {
            AiTurnStreamConsumer consumer = invocation.getArgument(1);
            consumer.onTextDelta("partial");
            consumer.onComplete(truncated);
            return null;
        }).when(gateway).stream(org.mockito.ArgumentMatchers.any(AiTurnRequest.class),
                org.mockito.ArgumentMatchers.any(AiTurnStreamConsumer.class));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("describe", Map.of(), List.of(), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(command))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("截断");
            assertThatThrownBy(() -> service.stream(command, new AiTurnStreamConsumer() {
                @Override
                public void onTextDelta(String text) {
                }

                @Override
                public void onComplete(AiTurnResponse response) {
                    throw new AssertionError("truncated responses must not complete");
                }
            })).isInstanceOf(PlatformException.class).hasMessageContaining("截断");
        }
    }

    @Test
    void rejectsToolCallsWithoutTheToolCallsTerminalReason() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(new AiTurnResponse(null,
                List.of(new AiToolCall("call-1", "page.describe", Map.of())), null, "request-incomplete"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("describe", Map.of(),
                List.of(new AiToolDefinition("page.describe", "Describe page", Map.of("type", "object"))), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(command))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("未完整结束");
        }
    }

    @Test
    void keepsSystemRulesServerOwnedAndForwardsOnlyDeclaredCapabilities() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AiTurnResponse response = new AiTurnResponse("ready", List.of(), "stop", "request-1");
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(response);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AiToolDefinition capability = new AiToolDefinition("workbench.find-menu", "Find visible menus",
                Map.of("type", "object"));
        AssistantTurnCommand command = new AssistantTurnCommand("find customers",
                Map.of("surface", "workbench"), List.of(capability), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-1"))) {
            assertThat(service.turn(command)).isSameAs(response);
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages()).hasSize(2);
        assertThat(request.getValue().messages().getFirst().role().name()).isEqualTo("SYSTEM");
        assertThat(request.getValue().messages().get(1).content()).contains("find customers", "workbench");
        assertThat(request.getValue().tools()).containsExactly(capability);
    }

    @Test
    void rejectsAnonymousAndUnboundedRequestsBeforeCallingTheModel() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("hello", Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> service.turn(command))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).httpStatus()).isEqualTo(401);

        List<AiToolDefinition> tooMany = Collections.nCopies(AssistantTurnService.MAX_CAPABILITIES + 1,
                new AiToolDefinition("duplicate-is-validated-later", "Capability", Map.of()));
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(new AssistantTurnCommand("hello", Map.of(), tooMany, List.of())))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("too many capabilities");
        }
    }

    @Test
    void rejectsCapabilityCallsOutsideTheDeclaredBoundedCatalog() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse(null,
                        List.of(new AiToolCall("call-1", "undeclared.capability", Map.of())),
                        "tool_calls", "request-1"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("find customers", Map.of(),
                List.of(new AiToolDefinition("workbench.find-menu", "Find menus", Map.of())), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(command))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("undeclared capability call");
        }
    }

    @Test
    void acceptsPreviousSurfaceResultsOutsideTheCurrentCapabilityCatalog() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("continued", List.of(), "stop", "request-2"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("continue", Map.of(),
                List.of(new AiToolDefinition("page.describe", "Describe page", Map.of())),
                List.of(new AssistantCapabilityResult("call-1", "other.capability", Map.of(), null, null)));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThat(service.turn(command).text()).isEqualTo("continued");
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages().get(1).content())
                .contains("other.capability", "call-1");
    }


    @Test
    void acceptsAnEmptyModelTurnOnlyAfterARecordedCapabilityResult() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AiTurnResponse empty = new AiTurnResponse(null, List.of(), "stop", "request-3");
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(
                    new AssistantTurnCommand("start", Map.of(), List.of(), List.of())))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("模型未返回可执行内容");

            AssistantTurnCommand continuation = new AssistantTurnCommand("continue", Map.of(), List.of(),
                    List.of(new AssistantCapabilityResult("call-1", "form.patch-draft",
                            Map.of("changedFields", List.of("title")), null, null)));
            assertThat(service.turn(continuation)).isSameAs(empty);

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new AiTurnResponse(null, List.of(), "length", "request-4"));
            assertThatThrownBy(() -> service.turn(continuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("截断");

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
            AssistantTurnCommand failedContinuation = new AssistantTurnCommand("continue", Map.of(), List.of(),
                    List.of(new AssistantCapabilityResult("call-2", "form.patch-draft", null,
                            "CAPABILITY_FAILED", "Capability execution failed")));
            assertThatThrownBy(() -> service.turn(failedContinuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("模型未返回可执行内容");
        }
    }
}
