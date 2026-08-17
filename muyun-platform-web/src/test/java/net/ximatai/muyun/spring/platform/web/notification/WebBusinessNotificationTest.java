package net.ximatai.muyun.spring.platform.web.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebBusinessNotificationTest {
    @Test
    void shouldNotExposeRecipientScopeToBrowserPayload() throws Exception {
        BusinessNotification notification = new BusinessNotification("notice-1", "demo.notice", "标题", null, "正文", true,
                new BusinessNotificationRecipients(false, List.of("tenant-1"), List.of("organization-1"),
                        List.of("department-1"), List.of("position-1"), List.of("user-1")), List.of());

        String json = new ObjectMapper().writeValueAsString(WebBusinessNotification.from(notification));

        assertThat(json).doesNotContain("recipients", "tenant-1", "organization-1", "department-1", "position-1", "user-1");
    }
}
