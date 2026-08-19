package net.ximatai.muyun.spring.platform.web;

/** Source-neutral detail slot and its form editor. */
public record ResolvedPageDetailDescriptor(String emptyDescription, String createTitle,
                                           ResolvedViewDescriptor display, ResolvedViewDescriptor editor,
                                           ResolvedPageDetailWorkspaceViewDescriptor workspaceView,
                                           boolean showSystemInfo) {
}
