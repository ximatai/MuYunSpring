package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A resource-qualified editor extension; it is not a second page root. */
public record PageDetailEditorContribution(String resource, ViewDefinition editor) {
    public PageDetailEditorContribution {
        String validResource = PlatformNameRules.requireIdentifier(resource, "resource");
        resource = validResource;
        if (editor == null || editor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("detail editor contribution requires a form editor");
        }
        if (editor.fields().stream().anyMatch(field -> !validResource.equals(field.fieldRef().relationCode()))) {
            throw new IllegalArgumentException("editor contribution fields must be qualified by resource: " + validResource);
        }
    }
}
