package net.ximatai.muyun.spring.ability.logging;

import java.util.Objects;

public record LoginLogEvent(BusinessLogContext context, LoginLogDetails details) implements BusinessLogEvent {
    public LoginLogEvent { context = Objects.requireNonNull(context, "context must not be null"); details = Objects.requireNonNull(details, "details must not be null"); }
    @Override public BusinessLogEventType eventType() { return BusinessLogEventType.LOGIN; }
}
