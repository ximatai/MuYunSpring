package net.ximatai.muyun.spring.web;

/**
 * Explicit identity of the persisted aggregate record that owns a reference
 * interaction. This is transport metadata, not a business form value.
 */
public record WebReferenceSource(String recordId) {
    public WebReferenceSource {
        recordId = recordId == null || recordId.isBlank() ? null : recordId.trim();
    }

    public boolean persisted() {
        return recordId != null;
    }
}
