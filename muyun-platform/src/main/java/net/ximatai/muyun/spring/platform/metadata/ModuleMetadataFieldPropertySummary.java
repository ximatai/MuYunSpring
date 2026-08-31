package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;

import java.util.List;

/** Effective metadata field property facts in one module-relation context. */
public record ModuleMetadataFieldPropertySummary(
        String fieldId,
        String fieldName,
        String fieldSpecAlias,
        MetadataFieldPropertyKind kind,
        Integer bindingVersion,
        Reference reference,
        Dictionary dictionary
) {
    public record Reference(
            String targetModuleAlias,
            String targetMetadataId,
            String targetKeyField,
            String targetLabelField,
            ReferenceCardinality cardinality,
            ReferenceTargetUnavailablePolicy targetUnavailablePolicy,
            List<String> projectionMappings
    ) {
    }

    public record Dictionary(
            String applicationAlias,
            String categoryAlias,
            OptionSelectionMode selectionMode
    ) {
    }
}
