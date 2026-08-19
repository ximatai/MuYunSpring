package net.ximatai.muyun.spring.platform.notification;

import java.util.Map;

/** Opens a platform page descriptor. The client owns actual router navigation. */
public record BusinessNotificationNavigateAction(
        String key,
        String label,
        String moduleAlias,
        String recordId,
        String pageMode,
        Map<String, String> query,
        String placement,
        boolean dismissOnSuccess
) implements BusinessNotificationAction {
    public BusinessNotificationNavigateAction {
        key = BusinessNotificationAction.required(key, "business notification action key");
        label = BusinessNotificationAction.required(label, "business notification action label");
        moduleAlias = BusinessNotificationAction.required(moduleAlias, "business notification navigation moduleAlias");
        recordId = recordId == null || recordId.isBlank() ? null : recordId.trim();
        pageMode = pageMode == null || pageMode.isBlank() ? "LIST" : pageMode.trim();
        query = query == null ? Map.of() : Map.copyOf(query);
        placement = BusinessNotificationAction.placement(placement);
    }

    public BusinessNotificationNavigateAction(String key, String label, String moduleAlias, String recordId,
                                              String pageMode, Map<String, String> query, boolean dismissOnSuccess) {
        this(key, label, moduleAlias, recordId, pageMode, query, "leading", dismissOnSuccess);
    }
}
