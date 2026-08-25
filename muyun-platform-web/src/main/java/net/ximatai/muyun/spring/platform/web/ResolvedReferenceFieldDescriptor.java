package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Client-safe target metadata for a static entity reference form field. */
public record ResolvedReferenceFieldDescriptor(String targetModuleAlias,
                                               ReferenceCardinality cardinality,
                                               String titleField,
                                               ReferencePickerMode pickerMode,
                                               ReferenceCandidateDelivery candidateDelivery,
                                               String resolvePath,
                                               java.util.List<net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency> candidateDependencies,
                                               java.util.List<ResolvedReferenceSelectionProjectionDescriptor> selectionProjections) {
    public ResolvedReferenceFieldDescriptor {
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        cardinality = cardinality == null ? ReferenceCardinality.ONE : cardinality;
        titleField = titleField == null || titleField.isBlank() ? null : titleField.trim();
        pickerMode = pickerMode == null ? ReferencePickerMode.AUTO : pickerMode;
        candidateDelivery = candidateDelivery == null
                ? ReferenceCandidateDelivery.TARGET_NAVIGATOR
                : candidateDelivery;
        resolvePath = resolvePath == null || resolvePath.isBlank() ? null : resolvePath.trim();
        candidateDependencies = candidateDependencies == null ? java.util.List.of()
                : java.util.List.copyOf(candidateDependencies);
        selectionProjections = selectionProjections == null ? java.util.List.of()
                : java.util.List.copyOf(selectionProjections);
    }

    /** Compatibility constructor for descriptors without a picker contract. */
    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality,
                                            String titleField) {
        this(targetModuleAlias, cardinality, titleField, ReferencePickerMode.AUTO,
                ReferenceCandidateDelivery.TARGET_NAVIGATOR, null, java.util.List.of(), java.util.List.of());
    }

    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality,
                                            String titleField, ReferencePickerMode pickerMode) {
        this(targetModuleAlias, cardinality, titleField, pickerMode, ReferenceCandidateDelivery.TARGET_NAVIGATOR, null,
                java.util.List.of(), java.util.List.of());
    }

    /** Compatibility constructor for descriptors without a read-side title projection. */
    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality) {
        this(targetModuleAlias, cardinality, null, ReferencePickerMode.AUTO,
                ReferenceCandidateDelivery.TARGET_NAVIGATOR, null, java.util.List.of(), java.util.List.of());
    }

    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality,
                                            String titleField, ReferencePickerMode pickerMode,
                                            ReferenceCandidateDelivery candidateDelivery) {
        this(targetModuleAlias, cardinality, titleField, pickerMode, candidateDelivery, null, java.util.List.of(),
                java.util.List.of());
    }

    /** Compatibility constructor for descriptors issued before candidate dependencies were explicit. */
    public ResolvedReferenceFieldDescriptor(String targetModuleAlias, ReferenceCardinality cardinality,
                                            String titleField, ReferencePickerMode pickerMode,
                                            ReferenceCandidateDelivery candidateDelivery, String resolvePath) {
        this(targetModuleAlias, cardinality, titleField, pickerMode, candidateDelivery, resolvePath,
                java.util.List.of(), java.util.List.of());
    }
}
