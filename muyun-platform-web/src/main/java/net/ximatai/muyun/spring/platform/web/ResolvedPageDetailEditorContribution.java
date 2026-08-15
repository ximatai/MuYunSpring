package net.ximatai.muyun.spring.platform.web;

/** Resolved child-resource detail editor extension. */
public record ResolvedPageDetailEditorContribution(String resource, ResolvedViewDescriptor editor) {
    public ResolvedPageDetailEditorContribution {
        if (resource == null || resource.isBlank() || editor == null || editor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("resolved detail editor contribution requires resource and form editor");
        }
    }
}
