package net.ximatai.muyun.spring.platform.assistant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.ai.AiModelGateway;
import net.ximatai.muyun.spring.platform.ai.AiChatMessage;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiTurnRequest;
import net.ximatai.muyun.spring.platform.ai.AiTurnResponse;
import net.ximatai.muyun.spring.platform.ai.AiTurnStreamConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

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
    void logsOnlyStructuredTurnFactsWithoutConversationOrBusinessContent() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("private model response", List.of(), "stop", "provider-request-secret"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("private user request",
                List.of(new AssistantConversationMessage(AssistantConversationMessage.Role.USER,
                        "private history")),
                Map.of("surface", "module-page", "record", "private record"), List.of(), List.of());
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantTurnService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("system", "System"))) {
            service.turn(command);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("Assistant turn started", "historyCount=1", "capabilityCount=0"))
                .anySatisfy(message -> assertThat(message)
                        .contains("Assistant turn completed", "finishReason=stop", "toolCallCount=0"))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("private user request", "private history", "private record",
                                "private model response", "provider-request-secret"));
    }

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
    void doesNotReportClientDeliveryFailureAsAModelFailure() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        doAnswer(invocation -> {
            AiTurnStreamConsumer consumer = invocation.getArgument(1);
            try {
                consumer.onComplete(new AiTurnResponse("ready", List.of(), "stop", "request-stream"));
            } catch (RuntimeException callbackFailure) {
                throw new PlatformException("model client wrapped the stream callback", callbackFailure);
            }
            return null;
        }).when(gateway).stream(org.mockito.ArgumentMatchers.any(AiTurnRequest.class),
                org.mockito.ArgumentMatchers.any(AiTurnStreamConsumer.class));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantTurnService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.stream(
                    new AssistantTurnCommand("describe", Map.of(), List.of(), List.of()),
                    new AiTurnStreamConsumer() {
                        @Override
                        public void onTextDelta(String text) {
                        }

                        @Override
                        public void onComplete(AiTurnResponse response) {
                            throw new IllegalStateException("private browser disconnect");
                        }
                    })).isInstanceOf(IllegalStateException.class).hasMessage("private browser disconnect");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("Assistant turn completed"))
                .noneSatisfy(message -> assertThat(message)
                        .contains("Assistant turn failed", "private browser disconnect"));
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
        assertThat(normalizeWhitespace(request.getValue().messages().getFirst().content()))
                .contains("MuYun workbench", "open only an exact", "returned menuId", "navigate manually")
                .doesNotContain("employee", "department", "daily report");
        assertThat(request.getValue().messages().get(1).content()).contains("find customers", "workbench");
        assertThat(request.getValue().tools()).containsExactly(capability);
    }

    @Test
    void suppliesStandardPageOperatingKnowledgeWithoutTurningItIntoBusinessWorkflow() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("ready", List.of(), "stop", "request-page-knowledge"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.turn(new AssistantTurnCommand("帮我新增一条记录",
                    Map.of("surface", "module-page", "facts", Map.of("editorMode", "view")),
                    List.of(new AiToolDefinition("workbench.find-menu", "Find visible menus", Map.of())), List.of()));
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        String prompt = normalizeWhitespace(request.getValue().messages().getFirst().content());
        assertThat(prompt)
                .contains("standard MuYun record workspace", "patch known ordinary fields together",
                        "only when the user asked to create or change", "already complete and must not start a draft",
                        "Leave drafts unsaved", "ask one concise question", "workbench navigation")
                .doesNotContain("employee", "department", "daily report");
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    @Test
    void preservesBoundedDialogueRolesBeforeTheCurrentPageAwareMessage() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("ready", List.of(), "stop", "request-history"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("演示租户",
                List.of(
                        new AssistantConversationMessage(AssistantConversationMessage.Role.USER,
                                "我要新增一名职员，帮我做"),
                        new AssistantConversationMessage(AssistantConversationMessage.Role.ASSISTANT,
                                "请告诉我要在哪个租户新增职员。")
                ),
                Map.of("surface", "employee"), List.of(), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.turn(command);
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages()).extracting(AiChatMessage::role)
                .containsExactly(AiChatMessage.Role.SYSTEM, AiChatMessage.Role.USER,
                        AiChatMessage.Role.ASSISTANT, AiChatMessage.Role.USER);
        assertThat(request.getValue().messages().get(1).content()).isEqualTo("我要新增一名职员，帮我做");
        assertThat(request.getValue().messages().get(2).content()).contains("在哪个租户");
        assertThat(request.getValue().messages().get(3).content()).contains("演示租户", "employee");
        assertThat(request.getValue().messages().getFirst().content())
                .contains("goal without breaking it into operational steps", "ask one concise clarification");
    }

    @Test
    void rejectsUnboundedDialogueHistory() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        List<AssistantConversationMessage> tooMany = Collections.nCopies(
                AssistantTurnService.MAX_HISTORY_MESSAGES + 1,
                new AssistantConversationMessage(AssistantConversationMessage.Role.USER, "continue"));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(
                    new AssistantTurnCommand("continue", tooMany, Map.of(), List.of(), List.of())))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("too many history messages");
            assertThatThrownBy(() -> service.turn(new AssistantTurnCommand("continue",
                    List.of(new AssistantConversationMessage(AssistantConversationMessage.Role.USER,
                            "x".repeat(AssistantTurnService.MAX_HISTORY_MESSAGE_LENGTH + 1))),
                    Map.of(), List.of(), List.of())))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("history message is too long");
        }
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
    void acceptsAnEmptyOrBlankModelTurnOnlyAfterARecordedCapabilityResult() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AiTurnResponse blank = new AiTurnResponse("\n\n", List.of(), "stop", "request-3");
        AiTurnResponse empty = new AiTurnResponse(null, List.of(), "stop", "request-4");
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(blank);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> service.turn(
                    new AssistantTurnCommand("start", Map.of(), List.of(), List.of())))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("模型未返回可执行内容");

            AssistantTurnCommand continuation = new AssistantTurnCommand("continue", Map.of(), List.of(),
                    List.of(new AssistantCapabilityResult("call-1", "form.patch-draft",
                            Map.of("changedFields", List.of("title")), null, null)));
            assertThat(service.turn(continuation)).isSameAs(blank);
            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
            assertThat(service.turn(continuation)).isSameAs(empty);

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new AiTurnResponse(null, List.of(), "length", "request-5"));
            assertThatThrownBy(() -> service.turn(continuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("截断");

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(blank);
            AssistantTurnCommand failedContinuation = new AssistantTurnCommand("continue", Map.of(), List.of(),
                    List.of(new AssistantCapabilityResult("call-2", "form.patch-draft", null,
                            "CAPABILITY_FAILED", "Capability execution failed")));
            assertThatThrownBy(() -> service.turn(failedContinuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("模型未返回可执行内容");
        }
    }
}
