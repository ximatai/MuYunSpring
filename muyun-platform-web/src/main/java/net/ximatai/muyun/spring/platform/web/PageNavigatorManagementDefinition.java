package net.ximatai.muyun.spring.platform.web;

/**
 * Declares that a navigator source is also manageable in place.
 *
 * <p>The source module remains the owner of its CRUD policy and form schema.
 * An optional named editor surface narrows the form rendered by the page
 * composition; when omitted, the source module's default editor is used.</p>
 */
public record PageNavigatorManagementDefinition(String editorSurface) {
    public PageNavigatorManagementDefinition {
        editorSurface = editorSurface == null || editorSurface.isBlank() ? null : editorSurface.trim();
    }
}
