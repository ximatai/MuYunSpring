package net.ximatai.muyun.spring.ability.logging;

/** Result of one append attempt. Duplicate event IDs are safely ignored. */
public record BusinessLogWriteResult(String eventId, Status status) {
    public enum Status { APPENDED, DUPLICATE_IGNORED }
}
