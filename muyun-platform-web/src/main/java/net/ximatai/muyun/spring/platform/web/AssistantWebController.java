package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ai.AiTurnResponse;
import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.assistant.AssistantCapabilityResult;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnCommand;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authenticated assistant turn endpoint. Capability execution stays in the owning browser surface. */
@RestController
@RequestMapping("/platform.assistant")
public class AssistantWebController {
    private final AssistantTurnService service;

    public AssistantWebController(AssistantTurnService service) {
        this.service = service;
    }

    @PostMapping("/turn")
    public AssistantTurnWebResponse turn(@RequestBody AssistantTurnWebRequest request) {
        if (request == null) throw new IllegalArgumentException("assistant turn request must not be null");
        AiTurnResponse response = service.turn(new AssistantTurnCommand(request.message(), request.context(),
                request.capabilities(), request.results().stream().map(AssistantCapabilityResultWeb::toDomain).toList()));
        return new AssistantTurnWebResponse(response.text(),
                response.toolCalls().stream().map(AssistantCapabilityCallWeb::from).toList(),
                response.finishReason(), response.requestId());
    }
}

record AssistantTurnWebRequest(String message,
                               Map<String, Object> context,
                               List<AiToolDefinition> capabilities,
                               List<AssistantCapabilityResultWeb> results) {
    AssistantTurnWebRequest {
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        results = results == null ? List.of() : List.copyOf(results);
    }
}

record AssistantCapabilityResultWeb(String callId, String capabilityCode, Object output,
                                    AssistantCapabilityErrorWeb error) {
    AssistantCapabilityResult toDomain() {
        return new AssistantCapabilityResult(callId, capabilityCode, output,
                error == null ? null : error.code(), error == null ? null : error.message());
    }
}

record AssistantCapabilityErrorWeb(String code, String message) {
}

record AssistantTurnWebResponse(String text,
                                List<AssistantCapabilityCallWeb> toolCalls,
                                String finishReason,
                                String requestId) {
}

record AssistantCapabilityCallWeb(String id, String code, Map<String, Object> input) {
    static AssistantCapabilityCallWeb from(AiToolCall call) {
        return new AssistantCapabilityCallWeb(call.id(), call.code(), call.arguments());
    }
}
