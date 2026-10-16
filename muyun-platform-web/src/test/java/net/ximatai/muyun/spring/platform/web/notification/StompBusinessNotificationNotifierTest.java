package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.platform.notification.*;
import net.ximatai.muyun.spring.web.realtime.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StompBusinessNotificationNotifierTest {
    @Test void sendsAllEnabledLanguagesForReceiverSelectionWithoutTranslatingBusinessArgumentsOrExposingRecipients() {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = new TextResources(catalog, Map.of("en", Map.of("task.title", "Task: {name}", "task.open", "Open workbench")));
        var messages = mock(RealtimeMessagePublisher.class);
        var notification = new BusinessNotification("id", "workflow", "待办：张三", null, "业务备注原文", true,
                new BusinessNotificationRecipients(false, List.of("private-tenant"), List.of(), List.of(), List.of(), List.of("recipient")),
                List.of(new BusinessNotificationNavigateAction("open", "打开工作台", "iam.workflow_workbench", null, "LIST", Map.of("tenantId", "actual-tenant"), true)))
                .withTextSources(Map.of("title", TextReference.code("task.title", "待办：{name}"),
                        "action/open/label", TextReference.code("task.open", "打开工作台")), Map.of("name", "张三"));
        try (var publisherLanguage = LanguageContext.use("en")) {
            new StompBusinessNotificationNotifier(messages, () -> texts, () -> catalog).notifyUser("recipient", notification);
        }
        var envelope = ArgumentCaptor.forClass(Object.class);
        verify(messages).sendToUser(eq("recipient"), eq(RealtimeDestinations.USER_BUSINESS_NOTIFICATIONS), envelope.capture());
        var body = (WebBusinessNotification) ((RealtimeEnvelope<?>) envelope.getValue()).payload();
        assertThat(body.textResources().get("title").languages().get("en").text()).isEqualTo("Task: 张三");
        assertThat(body.textResources().get("title").languages().get("zh-CN").text()).isEqualTo("待办：张三");
        assertThat(body.textResources().get("action/open/label").languages().get("en").text()).isEqualTo("Open workbench");
        assertThat(body.content()).isEqualTo("业务备注原文");
        assertThat(((BusinessNotificationNavigateAction) body.actions().getFirst()).query()).containsEntry("tenantId", "actual-tenant");
        assertThat(body.toString()).doesNotContain("private-tenant", "recipients");
        assertThat(notification.title()).isEqualTo("待办：张三");
    }
}
