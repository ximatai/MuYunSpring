package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.ai.AiTokenUsage;
import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.ai.AiTurnResponse;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnCommand;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnResult;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnService;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnStreamConsumer;
import net.ximatai.muyun.spring.platform.assistant.AssistantSelectionInteraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AssistantWebControllerTest {
    static Stream<Arguments> providerUsageCases() {
        return Stream.of(false, true).flatMap(streaming -> Arrays.asList(
                new AiTokenUsage(42L, 10L, 52L),
                new AiTokenUsage(0L, 0L, 0L),
                new AiTokenUsage(42L, null, null),
                (AiTokenUsage) null).stream().map(usage -> Arguments.of(streaming, usage)));
    }

    @ParameterizedTest
    @MethodSource("providerUsageCases")
    void preservesReportedAndUnknownUsageInJsonAndSse(boolean streaming, AiTokenUsage usage) throws Exception {
        var service = mock(AssistantTurnService.class);
        var result = new AssistantTurnResult("已核实", List.of(), null, "stop", "request-usage", usage);
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        doAnswer(invocation -> {
            AssistantTurnStreamConsumer consumer = invocation.getArgument(1);
            consumer.onComplete(result);
            return null;
        }).when(service).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var controller = new AssistantWebController(service);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-1"));
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-1")) {
            var initial = mvc.perform(post("/platform.assistant/turn" + (streaming ? "/stream" : ""))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"核实当前页面\",\"context\":{},\"capabilities\":[],\"results\":[]}"))
                    .andReturn();
            String body;
            if (streaming) {
                assertThat(initial.getRequest().isAsyncStarted()).isTrue();
                initial.getAsyncResult(2_000);
                String events = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(events).contains("event:complete");
                body = events.lines().filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5)).findFirst().orElseThrow();
            } else {
                assertThat(initial.getResponse().getStatus()).isEqualTo(200);
                body = initial.getResponse().getContentAsString(StandardCharsets.UTF_8);
            }
            var actual = mapper.readTree(body).path("usage");
            if (usage == null) assertThat(actual.isMissingNode() || actual.isNull()).isTrue();
            else assertThat(mapper.treeToValue(actual, AiTokenUsage.class)).isEqualTo(usage);
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void preservesSummaryBudgetFromJsonThroughTheWebBoundary() throws Exception {
        var service = mock(AssistantTurnService.class);
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AssistantTurnResult("已核实", List.of(), null, "stop", "summary"));
        var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"message":"核实当前业务","context":{},"capabilities":[],"results":[],
                 "executionBudget":{"phase":"summary","step":9,"normalLimit":8,"hardLimit":12}}
                """, AssistantTurnWebRequest.class);
        new AssistantWebController(service).turn(request);
        var command = ArgumentCaptor.forClass(AssistantTurnCommand.class);
        verify(service).turn(command.capture());
        assertThat(command.getValue().summaryOnly()).isTrue();
        assertThat(command.getValue().executionBudget()).isEqualTo(
                new AssistantTurnCommand.ExecutionBudget("summary", 9, 8, 12));
    }

    @Test
    void rejectsNullDialogueItemsAsAValidationError() {
        assertThatThrownBy(() -> new AssistantTurnWebRequest("continue",
                java.util.Collections.singletonList(null), Map.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history item");
    }

    @Test
    void treatsAnAlreadyDisconnectedEmitterAsACompletedStream() {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("response already closed")).when(emitter).complete();

        AssistantWebController.complete(emitter);

        verify(emitter).complete();
    }

    @Test
    void preservesValidationSemanticsForStreamingErrors() {
        var error = AssistantWebController.streamError(
                new IllegalArgumentException("assistant turn message must not be blank"));

        assertThat(error.code()).isEqualTo(PlatformErrorCodes.VALIDATION_FAILED);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.message()).isEqualTo("assistant turn message must not be blank");
    }

    @Test
    void restoresTheVerifiedWebRequestContextForStreamingModelRouting() throws Exception {
        AssistantTurnService service = mock(AssistantTurnService.class);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<Boolean> system = new AtomicReference<>();
        AtomicReference<String> trace = new AtomicReference<>();
        doAnswer(invocation -> {
            tenant.set(TenantContext.currentTenantId().orElse(null));
            system.set(TenantContext.isSystem());
            trace.set(MDC.get("traceId"));
            AssistantTurnStreamConsumer consumer = invocation.getArgument(1);
            consumer.onComplete(new AssistantTurnResult("ready", List.of(), null,
                    "stop", "request-stream"));
            completed.countDown();
            return null;
        }).when(service).stream(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(AssistantTurnStreamConsumer.class));
        AssistantWebController controller = new AssistantWebController(service);
        AssistantTurnWebRequest request = new AssistantTurnWebRequest("describe", Map.of(), List.of(), List.of());

        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-1"));
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-1");
             RequestTraceContext.Scope ignoredTrace = RequestTraceContext.use("trace-assistant")) {
            controller.stream(request);
        }

        try {
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(tenant.get()).isEqualTo("tenant-1");
            assertThat(system.get()).isFalse();
            assertThat(trace.get()).isEqualTo("trace-assistant");
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void adaptsBrowserResultsAndModelToolArgumentsWithoutLeakingTransportTypesIntoTheService() {
        AssistantTurnService service = mock(AssistantTurnService.class);
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(new AssistantTurnResult(null,
                List.of(new AiToolCall("call-2", "form.patch-draft", Map.of("changes", Map.of("title", "Done")))),
                null, "tool_calls", "request-1"));
        AssistantTurnWebRequest request = new AssistantTurnWebRequest("continue",
                List.of(
                        new AssistantConversationMessageWeb("user", "我要新增一名职员"),
                        new AssistantConversationMessageWeb("assistant", "请补充租户")
                ),
                Map.of("surface", "module-page"),
                List.of(new AiToolDefinition("form.patch-draft", "Patch form", Map.of("type", "object"))),
                List.of(new AssistantCapabilityResultWeb("call-1", "workbench.open-menu", Map.of(), "read",
                        Map.of("opened", true), null)));

        AssistantTurnWebResponse response = new AssistantWebController(service).turn(request);

        assertThat(response.toolCalls()).containsExactly(new AssistantCapabilityCallWeb("call-2",
                "form.patch-draft", Map.of("changes", Map.of("title", "Done"))));
        ArgumentCaptor<AssistantTurnCommand> command = ArgumentCaptor.forClass(AssistantTurnCommand.class);
        verify(service).turn(command.capture());
        assertThat(command.getValue().results().getFirst().output()).isEqualTo(Map.of("opened", true));
        assertThat(command.getValue().history()).extracting(item -> item.role().name())
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void mapsSelectionInteractionsAndStructuredAnswersAcrossTheWebBoundary() {
        AssistantTurnService service = mock(AssistantTurnService.class);
        var selection = new AssistantSelectionInteraction(
                "selection-1",
                "请选择环境",
                AssistantSelectionInteraction.InputPolicy.SELECTION_REQUIRED,
                AssistantSelectionInteraction.Presentation.OPTIONS,
                List.of(
                        new AssistantSelectionInteraction.Option("production", "生产环境"),
                        new AssistantSelectionInteraction.Option("staging", "预发布环境")));
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AssistantTurnResult(null, List.of(), selection, "tool_calls", "request-selection"));
        AssistantTurnWebRequest request = new AssistantTurnWebRequest(
                "生产环境", List.of(), Map.of(), List.of(), List.of(),
                new AssistantSelectionResponseWeb("selection-1", "production", "生产环境"));

        AssistantTurnWebResponse response = new AssistantWebController(service).turn(request);

        assertThat(response.selection()).satisfies(value -> {
            assertThat(value.interactionId()).isEqualTo("selection-1");
            assertThat(value.inputPolicy()).isEqualTo("selection_required");
            assertThat(value.options()).extracting(AssistantSelectionOptionWeb::id)
                    .containsExactly("production", "staging");
        });
        ArgumentCaptor<AssistantTurnCommand> command = ArgumentCaptor.forClass(AssistantTurnCommand.class);
        verify(service).turn(command.capture());
        assertThat(command.getValue().selectionResponse().optionId()).isEqualTo("production");
    }
    @Test
    void carriesDecisionFeedbackThroughJsonWithoutTreatingItAsACapabilityResult() throws Exception {
        var service = mock(AssistantTurnService.class);
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AssistantTurnResult("ready", List.of(), null, "stop", "request"));
        var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"message":"continue","context":{},"capabilities":[],"results":[],
                 "decisionFeedback":"undeclared-tool"}
                """, AssistantTurnWebRequest.class);
        var controller = new AssistantWebController(service);
        try {
            controller.turn(request);
            var command = ArgumentCaptor.forClass(AssistantTurnCommand.class);
            verify(service).turn(command.capture());
            assertThat(command.getValue().decisionFeedback()).isEqualTo("undeclared-tool");
            assertThat(command.getValue().results()).isEmpty();
        } finally {
            controller.closeStreams();
        }
    }

}
