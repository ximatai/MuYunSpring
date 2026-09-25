package net.ximatai.muyun.spring.platform.web;

import jakarta.annotation.PreDestroy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.assistant.AssistantCapabilityResult;
import net.ximatai.muyun.spring.platform.assistant.AssistantConversationMessage;
import net.ximatai.muyun.spring.platform.assistant.AssistantSelectionInteraction;
import net.ximatai.muyun.spring.platform.assistant.AssistantSelectionResponse;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnCommand;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnResult;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnService;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnStreamConsumer;
import net.ximatai.muyun.spring.web.WebRequestContext;
import net.ximatai.muyun.spring.web.PlatformWebError;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated assistant turn endpoint. Capability execution stays in the owning browser surface. */
@RestController
@RequestMapping("/platform.assistant")
public class AssistantWebController {
    private final AssistantTurnService service;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AssistantWebController(AssistantTurnService service) {
        this.service = service;
    }

    @PostMapping("/turn")
    public AssistantTurnWebResponse turn(@RequestBody AssistantTurnWebRequest request) {
        if (request == null) throw new IllegalArgumentException("assistant turn request must not be null");
        return response(service.turn(command(request)));
    }

    @PostMapping(value = "/turn/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AssistantTurnWebRequest request) {
        if (request == null) throw new IllegalArgumentException("assistant turn request must not be null");
        WebRequestContext requestContext = WebRequestContext.capture().orElseThrow(() ->
                new PlatformException(PlatformErrorCodes.AUTH_REQUIRED, 401,
                        "authentication is required for assistant turns"));
        AssistantTurnCommand command = command(request);
        SseEmitter emitter = new SseEmitter(75_000L);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                Future<?> current = task.get();
                if (current != null) current.cancel(true);
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        Future<?> submitted = streamExecutor.submit(requestContext.wrap(() -> stream(command, emitter, closed)));
        task.set(submitted);
        if (closed.get()) submitted.cancel(true);
        return emitter;
    }

    private void stream(AssistantTurnCommand command, SseEmitter emitter, AtomicBoolean closed) {
        try {
            service.stream(command, new AssistantTurnStreamConsumer() {
                @Override
                public void onTextDelta(String text) {
                    send(emitter, "text", Map.of("text", text), closed);
                }

                @Override
                public void onComplete(AssistantTurnResult response) {
                    send(emitter, "complete", response(response), closed);
                }
            });
            if (closed.compareAndSet(false, true)) complete(emitter);
        } catch (Exception error) {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(streamError(error)));
                } catch (Exception ignored) {
                    // The browser closing an assistant stream is a normal cancellation path.
                }
                complete(emitter);
            }
        }
    }

    static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // A disconnected browser has already completed the transport lifecycle.
        }
    }

    private static void send(SseEmitter emitter, String event, Object data, AtomicBoolean closed) {
        if (closed.get()) throw new AssistantStreamClosedException();
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException error) {
            throw new AssistantStreamClosedException();
        }
    }

    static PlatformWebError streamError(Exception error) {
        if (error instanceof PlatformException platformException) {
            return PlatformWebError.of(platformException);
        }
        if (error instanceof IllegalArgumentException && error.getMessage() != null) {
            return PlatformWebError.of(PlatformErrorCodes.VALIDATION_FAILED, 400, error.getMessage());
        }
        return PlatformWebError.of(PlatformErrorCodes.INTERNAL_ERROR, 500, "智能助手响应中断，请重试");
    }

    private static AssistantTurnCommand command(AssistantTurnWebRequest request) {
        return new AssistantTurnCommand(request.message(),
                request.history().stream().map(AssistantConversationMessageWeb::toDomain).toList(),
                request.context(), request.capabilities(),
                request.results().stream().map(AssistantCapabilityResultWeb::toDomain).toList(),
                request.selectionResponse() == null ? null : request.selectionResponse().toDomain());
    }

    private static AssistantTurnWebResponse response(AssistantTurnResult response) {
        return new AssistantTurnWebResponse(response.text(),
                response.toolCalls().stream().map(AssistantCapabilityCallWeb::from).toList(),
                AssistantSelectionWeb.from(response.selection()),
                response.finishReason(), response.requestId());
    }

    @PreDestroy
    void closeStreams() {
        streamExecutor.shutdownNow();
    }

    private static final class AssistantStreamClosedException extends RuntimeException {
    }
}

record AssistantTurnWebRequest(String message,
                               List<AssistantConversationMessageWeb> history,
                               Map<String, Object> context,
                               List<AiToolDefinition> capabilities,
                               List<AssistantCapabilityResultWeb> results,
                               AssistantSelectionResponseWeb selectionResponse) {
    AssistantTurnWebRequest {
        if (history != null && history.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("assistant history item must not be null");
        }
        history = history == null ? List.of() : List.copyOf(history);
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        results = results == null ? List.of() : List.copyOf(results);
    }

    AssistantTurnWebRequest(String message, Map<String, Object> context,
                            List<AiToolDefinition> capabilities,
                            List<AssistantCapabilityResultWeb> results) {
        this(message, List.of(), context, capabilities, results, null);
    }

    AssistantTurnWebRequest(String message, List<AssistantConversationMessageWeb> history,
                            Map<String, Object> context, List<AiToolDefinition> capabilities,
                            List<AssistantCapabilityResultWeb> results) {
        this(message, history, context, capabilities, results, null);
    }
}

record AssistantSelectionResponseWeb(String interactionId, String optionId, String label) {
    AssistantSelectionResponse toDomain() {
        return new AssistantSelectionResponse(interactionId, optionId, label);
    }
}

record AssistantConversationMessageWeb(String role, String text) {
    AssistantConversationMessage toDomain() {
        if (role == null) throw new IllegalArgumentException("assistant history role must not be null");
        AssistantConversationMessage.Role domainRole = switch (role) {
            case "user" -> AssistantConversationMessage.Role.USER;
            case "assistant" -> AssistantConversationMessage.Role.ASSISTANT;
            default -> throw new IllegalArgumentException("assistant history role is invalid");
        };
        return new AssistantConversationMessage(domainRole, text);
    }
}

record AssistantCapabilityResultWeb(String callId, String capabilityCode, Map<String, Object> input, String execution, Object output,
                                    AssistantCapabilityErrorWeb error) {
    AssistantCapabilityResult toDomain() {
        return new AssistantCapabilityResult(callId, capabilityCode, input, execution, output,
                error == null ? null : error.code(), error == null ? null : error.message());
    }
}

record AssistantCapabilityErrorWeb(String code, String message) {
}

record AssistantTurnWebResponse(String text,
                                List<AssistantCapabilityCallWeb> toolCalls,
                                AssistantSelectionWeb selection,
                                String finishReason,
                                String requestId) {
}

record AssistantSelectionWeb(String interactionId,
                             String prompt,
                             String inputPolicy,
                             String presentation,
                             List<AssistantSelectionOptionWeb> options) {
    static AssistantSelectionWeb from(AssistantSelectionInteraction selection) {
        if (selection == null) return null;
        return new AssistantSelectionWeb(
                selection.interactionId(),
                selection.prompt(),
                selection.inputPolicy() == AssistantSelectionInteraction.InputPolicy.FREE_TEXT_ALLOWED
                        ? "free_text_allowed" : "selection_required",
                selection.presentation() == AssistantSelectionInteraction.Presentation.CONFIRMATION
                        ? "confirmation" : "options",
                selection.options().stream().map(AssistantSelectionOptionWeb::from).toList());
    }
}

record AssistantSelectionOptionWeb(String id, String label) {
    static AssistantSelectionOptionWeb from(AssistantSelectionInteraction.Option option) {
        return new AssistantSelectionOptionWeb(option.id(), option.label());
    }
}

record AssistantCapabilityCallWeb(String id, String code, Map<String, Object> input) {
    static AssistantCapabilityCallWeb from(AiToolCall call) {
        return new AssistantCapabilityCallWeb(call.id(), call.code(), call.arguments());
    }
}
