package net.ximatai.muyun.spring.ability.logging;

/** A typed, immutable business logging fact. */
public sealed interface BusinessLogEvent permits LoginLogEvent, ActionLogEvent, RequestErrorLogEvent, PageAccessLogEvent {
    BusinessLogContext context();

    BusinessLogEventType eventType();

    BusinessLogDetails details();

    default String eventId() { return context().eventId(); }
    default String traceId() { return context().traceId(); }
}
