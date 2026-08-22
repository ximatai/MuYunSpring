package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Compiles platform-owned reference facts into the source-neutral page descriptor. */
final class ReferenceFieldDescriptorCompiler {
    private ReferenceFieldDescriptorCompiler() {
    }

    /**
     * A tree parent is a platform-owned self reference rather than an application-declared
     * reference. It therefore belongs to the shared descriptor contract for both static and
     * dynamic modules.
     */
    static Map<String, ResolvedReferenceFieldDescriptor> withTreeParentReference(
            String moduleAlias,
            boolean treeEnabled,
            Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
            Function<String, ReferencePickerMode> pickerModeResolver) {
        Map<String, ResolvedReferenceFieldDescriptor> resolved = referenceFields == null ? Map.of() : referenceFields;
        if (!treeEnabled) {
            return resolved;
        }
        ResolvedReferenceFieldDescriptor explicit = resolved.get(PlatformAbilityFields.TREE_PARENT_FIELD);
        if (explicit != null) {
            if (moduleAlias.equals(explicit.targetModuleAlias())
                    && explicit.cardinality() == ReferenceCardinality.ONE) {
                return resolved;
            }
            throw new IllegalArgumentException("tree parent reference must be a single self reference: " + moduleAlias);
        }
        Map<String, ResolvedReferenceFieldDescriptor> augmented = new LinkedHashMap<>(resolved);
        augmented.put(PlatformAbilityFields.TREE_PARENT_FIELD,
                new ResolvedReferenceFieldDescriptor(moduleAlias, ReferenceCardinality.ONE, null,
                        pickerModeResolver.apply(moduleAlias)));
        return Map.copyOf(augmented);
    }
}
