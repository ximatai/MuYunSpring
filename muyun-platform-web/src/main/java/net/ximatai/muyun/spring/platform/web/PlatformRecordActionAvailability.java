package net.ximatai.muyun.spring.platform.web;

import java.util.List;

public record PlatformRecordActionAvailability(
        String recordId,
        List<Action> actions
) {
    public PlatformRecordActionAvailability {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public record Action(
            String actionCode,
            boolean available,
            String reason,
            net.ximatai.muyun.spring.common.i18n.TextReference reasonSource
    ) {
        public Action(String actionCode, boolean available, String reason) { this(actionCode, available, reason, null); }
    }
}
