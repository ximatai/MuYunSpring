package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AiToolMessageContractTest {
    private final AiToolCall call = new AiToolCall("call-1", "page.old-tool", Map.of("title", "示例"));

    @Test
    void acceptsHistoricalToolsOutsideTheCurrentCatalogAndKeepsLatestContextSeparate() {
        var messages = List.of(AiChatMessage.call(call), AiChatMessage.result("call-1", "{\"execution\":\"effect-applied\"}"),
                new AiChatMessage(AiChatMessage.Role.USER, "latest page facts"));
        var request = new AiTurnRequest(messages, List.of(), null, 512);
        assertThat(request.messages()).containsExactlyElementsOf(messages);
    }

    @Test
    void rejectsOrphanDuplicateMissingAndInterruptedToolPairs() {
        var result = AiChatMessage.result("call-1", "done");
        var request = AiChatMessage.call(call);
        for (var messages : List.of(List.of(result), List.of(request), List.of(request, result, result),
                List.of(request, result, request, result),
                List.of(request, new AiChatMessage(AiChatMessage.Role.USER, "next"), result))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AiTurnRequest(messages, List.of(), null, 512));
        }
    }

    @Test
    void rejectsRoleConfusionAndToolsInTextOnlyRequests() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AiChatMessage(AiChatMessage.Role.USER, "hello", List.of(call), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AiChatMessage(AiChatMessage.Role.TOOL, "done"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AiTextRequest(List.of(AiChatMessage.call(call)), null, 512));
    }

    @Test
    void toolIdentityIsProviderSafeAndIndependentOfCatalogPosition() {
        assertThat(OpenAiCompatibleModelClient.providerToolName("page.old-tool"))
                .matches("cap_[a-f0-9]{56}")
                .isNotEqualTo(OpenAiCompatibleModelClient.providerToolName("page.old_tool"));
    }
}
