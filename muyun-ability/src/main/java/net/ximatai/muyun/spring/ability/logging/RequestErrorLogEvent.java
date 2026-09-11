package net.ximatai.muyun.spring.ability.logging;

import java.util.Objects;

public record RequestErrorLogEvent(BusinessLogContext context, RequestErrorLogDetails details) implements BusinessLogEvent {
    public RequestErrorLogEvent { context = Objects.requireNonNull(context, "context must not be null"); details = Objects.requireNonNull(details, "details must not be null"); }
    @Override public BusinessLogEventType eventType() { return BusinessLogEventType.REQUEST_ERROR; }
}
