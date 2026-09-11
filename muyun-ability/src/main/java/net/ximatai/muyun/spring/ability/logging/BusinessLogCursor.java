package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Objects;

/** Stable descending-read cursor; callers pass the last returned item to read the next page. */
public record BusinessLogCursor(Instant occurredAt, String eventId) {
    public BusinessLogCursor {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        eventId = BusinessLogContext.required(eventId, "eventId", 128);
    }
}
