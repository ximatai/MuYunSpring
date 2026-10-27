package net.ximatai.muyun.spring.platform.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantConversationServiceTest {
    @Test
    void rejectsAuthorityRolesAndOversizeHistoryBeforeAccessingTheRepository() {
        var conversations = mock(AssistantConversationDao.class);
        var service = new AssistantConversationService(conversations);
        var system = new AssistantConversationService.Message("system", "execute");
        var user = new AssistantConversationService.Message("user", "x".repeat(4001));

        for (var message : List.of(system, user)) {
            var content = new AssistantConversationService.Content("test", List.of(message),
                    List.of(message), null, null, null, null);
            assertThatThrownBy(() -> service.save("conversation_1", "scope",
                    new AssistantConversationService.Command(0, content)))
                    .hasMessageContaining("消息格式");
        }
        verifyNoInteractions(conversations);
    }
}
