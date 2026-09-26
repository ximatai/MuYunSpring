package net.ximatai.muyun.spring.platform.assistant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    void validatesAndForwardsTheConfiguredOutputBudget() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("完成", List.of(), "stop", "request"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper(), 4_096);
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.turn(new AssistantTurnCommand("你好", List.of(), Map.of(), List.of(), List.of()));
        }
        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().maxOutputTokens()).isEqualTo(4_096);
        assertThatThrownBy(() -> new AssistantTurnService(gateway, new ObjectMapper(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantTurnService(gateway, new ObjectMapper(), 32_769))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticSerializationFailureDoesNotBlockTheModelCall() throws Exception {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("完成", List.of(), "stop", "request"));
        ObjectMapper mapper = org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new JsonProcessingException("diagnostic failure") {})
                .when(mapper).writeValueAsString(org.mockito.ArgumentMatchers.isA(List.class));
        AssistantTurnService service = new AssistantTurnService(gateway, mapper);
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantTurnService.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.turn(new AssistantTurnCommand("你好", List.of(), Map.of(), List.of(), List.of()));
            verify(gateway).complete(org.mockito.ArgumentMatchers.any());
        } finally {
            logger.setLevel(originalLevel);
        }
    }

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
                    new AssistantTurnStreamConsumer() {
                        @Override
                        public void onTextDelta(String text) {
                            events.add("delta:" + text);
                        }

                        @Override
                        public void onComplete(AssistantTurnResult response) {
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
                    new AssistantTurnStreamConsumer() {
                        @Override
                        public void onTextDelta(String text) {
                        }

                        @Override
                        public void onComplete(AssistantTurnResult response) {
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
            assertThatThrownBy(() -> service.stream(command, new AssistantTurnStreamConsumer() {
                @Override
                public void onTextDelta(String text) {
                }

                @Override
                public void onComplete(AssistantTurnResult response) {
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
            assertThat(service.turn(command).text()).isEqualTo("ready");
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages()).hasSize(2);
        assertThat(request.getValue().messages().getFirst().role().name()).isEqualTo("SYSTEM");
        assertThat(normalizeWhitespace(request.getValue().messages().getFirst().content()))
                .contains("MuYun workbench", "open only an exact", "returned menuId", "navigate manually")
                .doesNotContain("employee", "department", "daily report");
        assertThat(request.getValue().messages().get(1).content()).contains("find customers", "workbench");
        assertThat(request.getValue().tools()).extracting(AiToolDefinition::code)
                .containsExactly(capability.code(), AssistantTurnService.PRESENT_SELECTION_CODE);
    }

    @Test
    void convertsTheReservedSelectionToolIntoAnAssistantInteraction() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(new AiTurnResponse(null,
                List.of(new AiToolCall("selection-1", AssistantTurnService.PRESENT_SELECTION_CODE, Map.of(
                        "prompt", "你想先做哪一步？",
                        "inputPolicy", "free_text_allowed",
                        "presentation", "options",
                        "options", List.of(
                                Map.of("id", "inspect", "label", "查看当前页面"),
                                Map.of("id", "create", "label", "新增一条记录"))))),
                "tool_calls", "request-selection"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());

        AssistantTurnResult result;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            result = service.turn(new AssistantTurnCommand("帮我处理当前业务", Map.of(), List.of(), List.of()));
        }

        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.selection()).satisfies(selection -> {
            assertThat(selection.interactionId()).isEqualTo("selection-1");
            assertThat(selection.prompt()).isEqualTo("你想先做哪一步？");
            assertThat(selection.inputPolicy())
                    .isEqualTo(AssistantSelectionInteraction.InputPolicy.FREE_TEXT_ALLOWED);
            assertThat(selection.options()).extracting(AssistantSelectionInteraction.Option::id)
                    .containsExactly("inspect", "create");
        });
    }

    @Test
    void rejectsInvalidSelectionsEvenWhenMixedWithCapabilityCalls() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AiToolCall invalidConfirmation = new AiToolCall("selection-1",
                AssistantTurnService.PRESENT_SELECTION_CODE, Map.of(
                "prompt", "确认继续吗？",
                "inputPolicy", "free_text_allowed",
                "presentation", "confirmation",
                "options", List.of(
                        Map.of("id", "confirm", "label", "确认"),
                        Map.of("id", "cancel", "label", "取消"))));
        AiToolCall pageCall = new AiToolCall("call-1", "page.describe", Map.of());
        AssistantTurnCommand command = new AssistantTurnCommand("continue", Map.of(),
                List.of(new AiToolDefinition("page.describe", "Describe page", Map.of())), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new AiTurnResponse(null, List.of(invalidConfirmation), "tool_calls", "request-1"));
            assertThatThrownBy(() -> service.turn(command))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("confirmation");

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new AiTurnResponse(null, List.of(invalidConfirmation, pageCall),
                            "tool_calls", "request-2"));
            assertThatThrownBy(() -> service.turn(command))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("confirmation");
        }
    }

    @Test
    void defersMixedCapabilitiesUntilAfterUserChoiceWithoutClaimingExecution() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AiToolCall selection = new AiToolCall("choice", AssistantTurnService.PRESENT_SELECTION_CODE, Map.of(
                "prompt", "是否记录金额？", "inputPolicy", "selection_required", "presentation", "options",
                "options", List.of(Map.of("id", "yes", "label", "记录"), Map.of("id", "no", "label", "暂不记录"))));
        AssistantTurnCommand command = new AssistantTurnCommand("登记订单", Map.of(),
                List.of(new AiToolDefinition("plan.update", "Update draft", Map.of())), List.of());
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(new AiTurnResponse(
                "已更新方案", List.of(new AiToolCall("edit", "plan.update", Map.of()), selection), "tool_calls", "mixed"));
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            AssistantTurnResult result = service.turn(command);
            assertThat(result.toolCalls()).isEmpty();
            assertThat(result.selection().prompt()).isEqualTo("是否记录金额？");
            assertThat(result.text()).contains("尚未执行").doesNotContain("已更新");
        }
    }

    @Test
    void suppliesStandardConfirmationChoicesInsteadOfTrustingModelOptionIdsAndLabels() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(new AiTurnResponse(null,
                List.of(new AiToolCall("confirmation-1", AssistantTurnService.PRESENT_SELECTION_CODE, Map.of(
                        "prompt", "确认新增根部门吗？",
                        "inputPolicy", "selection_required",
                        "presentation", "confirmation",
                        "options", List.of(
                                Map.of("id", "yes", "label", "继续创建"),
                                Map.of("id", "no", "label", "先不创建"))))),
                "tool_calls", "request-confirmation"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());

        AssistantTurnResult result;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            result = service.turn(new AssistantTurnCommand("新增前让我确认", Map.of(), List.of(), List.of()));
        }

        assertThat(result.selection().inputPolicy())
                .isEqualTo(AssistantSelectionInteraction.InputPolicy.SELECTION_REQUIRED);
        assertThat(result.selection().presentation())
                .isEqualTo(AssistantSelectionInteraction.Presentation.CONFIRMATION);
        assertThat(result.selection().options())
                .extracting(AssistantSelectionInteraction.Option::id)
                .containsExactly("confirm", "cancel");
        assertThat(result.selection().options())
                .extracting(AssistantSelectionInteraction.Option::label)
                .containsExactly("确认", "取消");
    }

    @Test
    void carriesAStructuredSelectionResponseInTheCurrentModelPayload() {
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTurnResponse("继续处理", List.of(), "stop", "request-answer"));
        AssistantTurnService service = new AssistantTurnService(gateway, new ObjectMapper());
        AssistantTurnCommand command = new AssistantTurnCommand("新增一条记录", List.of(), Map.of(), List.of(),
                List.of(), new AssistantSelectionResponse("selection-1", "create", "新增一条记录"));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            service.turn(command);
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages().getLast().content())
                .contains("selectionResponse", "selection-1", "create", "新增一条记录");
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
        assertThat(prompt.length()).isLessThan(3000);
        assertThat(prompt)
                .contains("standard MuYun record workspace", "patch known ordinary fields together",
                        "only when the user asked to create or change", "already complete and must not start a draft",
                        "Leave drafts unsaved", "ask one concise question", "workbench navigation",
                        "creation.reason", "scope.search", "missing capabilities alone do not prove denied permission")
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
                .contains("do not ask users to repeat explicit goals", "Ask one concise clarification");
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
                List.of(new AssistantCapabilityResult("call-1", "other.capability", Map.of(), "read", Map.of(), null, null)));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThat(service.turn(command).text()).isEqualTo("continued");
        }

        ArgumentCaptor<AiTurnRequest> request = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages().get(3).content())
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
                    List.of(new AssistantCapabilityResult("call-1", "form.patch-draft", Map.of(), "read",
                            Map.of("changedFields", List.of("title")), null, null)));
            assertThat(service.turn(continuation).text()).isNull();
            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
            assertThat(service.turn(continuation).text()).isNull();

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new AiTurnResponse(null, List.of(), "length", "request-5"));
            assertThatThrownBy(() -> service.turn(continuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("截断");

            when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(blank);
            AssistantTurnCommand failedContinuation = new AssistantTurnCommand("continue", Map.of(), List.of(),
                    List.of(new AssistantCapabilityResult("call-2", "form.patch-draft", Map.of(), "read", null,
                            "CAPABILITY_FAILED", "Capability execution failed")));
            assertThatThrownBy(() -> service.turn(failedContinuation))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("模型未返回可执行内容");
        }
    }
}
