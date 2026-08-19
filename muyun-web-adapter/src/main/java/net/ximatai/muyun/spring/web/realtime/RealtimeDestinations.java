package net.ximatai.muyun.spring.web.realtime;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class RealtimeDestinations {
    public static final RealtimeQueue DATA_CHANGES = RealtimeQueue.of("/queue/platform/data-changes");
    public static final RealtimeQueue USER_NOTIFICATIONS = RealtimeQueue.of("/queue/platform/notifications");
    public static final RealtimeQueue USER_BUSINESS_NOTIFICATIONS = RealtimeQueue.of("/queue/platform/business-notifications");
    public static final RealtimeQueue USER_BUSINESS_EVENTS = RealtimeQueue.of("/queue/platform/business-events");
    public static final RealtimeQueue USER_IM_MESSAGES = RealtimeQueue.of("/queue/platform/im/messages");
    public static final RealtimeCommand PLATFORM_PING = RealtimeCommand.of("/app/platform/ping");
    public static final RealtimeCommand SESSION_ACTIVITY = RealtimeCommand.of("/app/platform/session/activity");
    public static final RealtimeCommand IM_MESSAGES_SEND = RealtimeCommand.of("/app/platform/im/messages/send");

    private RealtimeDestinations() {
    }

    public static RealtimeTopic tenantPublicDataChanges(String tenantId) {
        return RealtimeTopic.of("/topic/platform/tenants/" + pathSegment(tenantId) + "/public/data-changes");
    }

    public static RealtimeTopic tenantPublicNotifications(String tenantId) {
        return RealtimeTopic.of("/topic/platform/tenants/" + pathSegment(tenantId) + "/public/notifications");
    }

    public static RealtimeTopic organizationPublicDataChanges(String organizationId) {
        return RealtimeTopic.of("/topic/platform/organizations/" + pathSegment(organizationId)
                + "/public/data-changes");
    }

    public static RealtimeTopic organizationPublicNotifications(String organizationId) {
        return RealtimeTopic.of("/topic/platform/organizations/" + pathSegment(organizationId)
                + "/public/notifications");
    }

    public static RealtimeTopic moduleDataChanges(String moduleAlias) {
        return RealtimeTopic.of("/topic/platform/modules/" + pathSegment(moduleAlias) + "/data-changes");
    }

    public static RealtimeTopic recordDataChanges(String moduleAlias, String recordId) {
        return RealtimeTopic.of("/topic/platform/modules/" + pathSegment(moduleAlias) + "/records/"
                + pathSegment(recordId) + "/data-changes");
    }

    public static RealtimeTopic resourceDataChanges(String moduleAlias, String resourceKey) {
        return RealtimeTopic.of("/topic/platform/modules/" + pathSegment(moduleAlias) + "/resources/"
                + pathSegment(resourceKey) + "/data-changes");
    }

    public static RealtimeTopic resourceRecordDataChanges(String moduleAlias, String resourceKey, String recordId) {
        return RealtimeTopic.of("/topic/platform/modules/" + pathSegment(moduleAlias) + "/resources/"
                + pathSegment(resourceKey) + "/records/" + pathSegment(recordId) + "/data-changes");
    }

    public static RealtimeTopic contextDataChanges(String contextType, String contextId) {
        return RealtimeTopic.of("/topic/platform/contexts/" + pathSegment(contextType) + "/"
                + pathSegment(contextId) + "/data-changes");
    }

    public static RealtimeTopic imConversationMessages(String conversationId) {
        return RealtimeTopic.of("/topic/platform/im/conversations/" + pathSegment(conversationId) + "/messages");
    }

    private static String pathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("realtime destination path segment must not be blank");
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
