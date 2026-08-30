package net.ximatai.muyun.spring.platform.web;

/** Source-neutral descriptor produced from one visible revision and an unsaved tree. */
public record PresentationRevisionPreview(String pageId,
                                          String variantId,
                                          String revisionId,
                                          ResolvedModuleUiDescriptor uiDescriptor) {
}
