package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;

public record FieldBehaviorDefinition(
        String defaultValue,
        String validationRegex,
        boolean copyable,
        boolean writeProtected,
        FieldWriteRules writeRules
) {
    public static final FieldBehaviorDefinition DEFAULT = new FieldBehaviorDefinition(null, null, true, false);

    public FieldBehaviorDefinition(String defaultValue, String validationRegex, boolean copyable, boolean writeProtected) {
        this(defaultValue, validationRegex, copyable, writeProtected, FieldWriteRules.NONE);
    }

    public FieldBehaviorDefinition {
        writeRules = writeRules == null ? FieldWriteRules.NONE : writeRules;
        if (validationRegex != null && validationRegex.isBlank()) {
            validationRegex = null;
        }
    }
}
