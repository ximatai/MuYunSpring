package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Server-side form field rules compiled from a published module editor. */
public record ModuleMutationFieldValidation(String relationAlias, String fieldName,
                                            boolean readOnly, boolean required) {
    public ModuleMutationFieldValidation {
        relationAlias = relationAlias == null || relationAlias.isBlank() ? null
                : PlatformNameRules.requireIdentifier(relationAlias, "mutation relation alias");
        fieldName = PlatformNameRules.requireFieldName(fieldName, "mutation field");
    }
}
