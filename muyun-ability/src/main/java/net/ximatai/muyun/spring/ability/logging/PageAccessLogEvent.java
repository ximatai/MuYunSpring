package net.ximatai.muyun.spring.ability.logging;

import java.util.Objects;

public record PageAccessLogEvent(BusinessLogContext context, PageAccessLogDetails details) implements BusinessLogEvent {
    public PageAccessLogEvent { context = Objects.requireNonNull(context, "context must not be null"); details = Objects.requireNonNull(details, "details must not be null"); }
    @Override public BusinessLogEventType eventType() { return BusinessLogEventType.PAGE_ACCESS; }
}
