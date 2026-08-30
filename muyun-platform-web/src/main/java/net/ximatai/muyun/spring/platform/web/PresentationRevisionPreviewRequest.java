package net.ximatai.muyun.spring.platform.web;

/** Transient page tree submitted by the composer; it is never persisted by preview. */
public record PresentationRevisionPreviewRequest(String uiTreeJson) {
}
