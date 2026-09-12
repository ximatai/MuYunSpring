package net.ximatai.muyun.spring.ability.logging;

import java.util.Objects;

public record ActionLogEvent(BusinessLogContext context, ActionLogDetails details) implements BusinessLogEvent {
    public ActionLogEvent { context = Objects.requireNonNull(context, "context must not be null"); details = Objects.requireNonNull(details, "details must not be null"); }
    @Override public BusinessLogEventType eventType() { return BusinessLogEventType.ACTION; }
}
