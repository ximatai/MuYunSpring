package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import net.ximatai.muyun.spring.platform.ai.AiTurnResponse;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnCommand;
import net.ximatai.muyun.spring.platform.assistant.AssistantTurnService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantWebControllerTest {
    @Test
    void adaptsBrowserResultsAndModelToolArgumentsWithoutLeakingTransportTypesIntoTheService() {
        AssistantTurnService service = mock(AssistantTurnService.class);
        when(service.turn(org.mockito.ArgumentMatchers.any())).thenReturn(new AiTurnResponse(null,
                List.of(new AiToolCall("call-2", "form.patch-draft", Map.of("changes", Map.of("title", "Done")))),
                "tool_calls", "request-1"));
        AssistantTurnWebRequest request = new AssistantTurnWebRequest("continue",
                Map.of("surface", "module-page"),
                List.of(new AiToolDefinition("form.patch-draft", "Patch form", Map.of("type", "object"))),
                List.of(new AssistantCapabilityResultWeb("call-1", "workbench.open-menu",
                        Map.of("opened", true), null)));

        AssistantTurnWebResponse response = new AssistantWebController(service).turn(request);

        assertThat(response.toolCalls()).containsExactly(new AssistantCapabilityCallWeb("call-2",
                "form.patch-draft", Map.of("changes", Map.of("title", "Done"))));
        ArgumentCaptor<AssistantTurnCommand> command = ArgumentCaptor.forClass(AssistantTurnCommand.class);
        verify(service).turn(command.capture());
        assertThat(command.getValue().results().getFirst().output()).isEqualTo(Map.of("opened", true));
    }
}
