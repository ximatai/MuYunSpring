package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Client-safe target metadata for a static entity reference form field. */
public record ResolvedReferenceFieldDescriptor(String targetModuleAlias,
                                               ReferenceCardinality cardinality,
                                               String titleField,
                                               ReferencePickerMode pickerMode) {
    public ResolvedReferenceFieldDescriptor {
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        cardinality = cardinality == null ? ReferenceCardinality.ONE : cardinality;
        titleField = titleField == null || titleField.isBlank() ? null : titleField.trim();
        pickerMode = pickerMode == null ? ReferencePickerMode.AUTO : pickerMode;
    }

    /** Compatibility constructor for descriptors without a picker contract. */
    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality,
                                            String titleField) {
        this(targetModuleAlias, cardinality, titleField, ReferencePickerMode.AUTO);
    }

    /** Compatibility constructor for descriptors without a read-side title projection. */
    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality) {
        this(targetModuleAlias, cardinality, null, ReferencePickerMode.AUTO);
    }
}
