package net.ximatai.muyun.spring.platform.web;

/**
 * Declares that a persisted detail record may be opened as a separately
 * restorable workbench view. The matching client implementation is registered
 * by this stable type; no frontend component identity crosses the web DSL.
 */
public record PageDetailWorkspaceViewDefinition(String type) {
    public PageDetailWorkspaceViewDefinition {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("page detail workspace view type must not be blank");
        }
        type = type.trim();
    }
}
