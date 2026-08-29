package net.ximatai.muyun.spring.platform.metadata;

/** Structured validation feedback for an edit-session proposal. */
public record MetadataChangeSetValidationIssue(Severity severity, String code, String subject, String message) {
    public enum Severity { WARNING, ERROR }
}
