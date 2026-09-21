package net.ximatai.muyun.spring.ability.logging;

/** Raised when a stale administrator edit loses an optimistic retention-policy update. */
public final class BusinessLogRetentionPolicyConflictException extends RuntimeException {
    private final BusinessLogEventType eventType;

    public BusinessLogRetentionPolicyConflictException(BusinessLogEventType eventType) {
        super("Business-log retention policy was concurrently updated: " + eventType);
        this.eventType = eventType;
    }

    public BusinessLogEventType eventType() {
        return eventType;
    }
}
