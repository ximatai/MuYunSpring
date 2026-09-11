package net.ximatai.muyun.spring.platform.web.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationTone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebBusinessNotificationTest {
    @Test
    void shouldNotExposeRecipientScopeToBrowserPayload() throws Exception {
        BusinessNotification notification = new BusinessNotification("notice-1", "demo.notice", "标题", null, "正文", true,
                new BusinessNotificationRecipients(false, List.of("tenant-1"), List.of("organization-1"),
                        List.of("department-1"), List.of("position-1"), List.of("user-1")), List.of());

        String json = new ObjectMapper().writeValueAsString(WebBusinessNotification.from(notification));

        assertThat(json).doesNotContain("recipients", "tenant-1", "organization-1", "department-1", "position-1", "user-1",
                "tone", "occurredAt");
    }

    @Test
    void shouldProjectOptionalPresentationMetadata() {
        Instant occurredAt = Instant.parse("2026-09-11T06:57:39Z");
        BusinessNotification notification = new BusinessNotification("notice-1", "helmet.presence.online", "安全帽已上线",
                "设备：HELMET-LOCAL-001", "已连接到设备接入服务。", true,
                BusinessNotificationRecipients.none(), List.of(), BusinessNotificationTone.SUCCESS, occurredAt);

        WebBusinessNotification payload = WebBusinessNotification.from(notification);

        assertThat(payload.tone()).isEqualTo("success");
        assertThat(payload.occurredAt()).isEqualTo(occurredAt);
    }
}
