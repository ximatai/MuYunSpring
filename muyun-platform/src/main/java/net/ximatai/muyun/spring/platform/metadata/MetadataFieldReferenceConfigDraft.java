package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;

import java.util.List;

/**
 * JSON-facing reference binding proposal.  Storage keeps projection mappings as one compact
 * string, while an edit-session command intentionally uses an ordered list of mappings.
 */
public record MetadataFieldReferenceConfigDraft(
        String targetModuleAlias,
        String targetMetadataId,
        String targetKeyField,
        String targetLabelField,
        ReferenceCardinality cardinality,
        ReferenceTargetUnavailablePolicy targetUnavailablePolicy,
        List<String> projectionMappings
) {
    public MetadataFieldReferenceConfigDraft {
        projectionMappings = projectionMappings == null ? List.of() : List.copyOf(projectionMappings);
    }

    public MetadataFieldReferenceConfig toConfig() {
        MetadataFieldReferenceConfig result = new MetadataFieldReferenceConfig();
        result.setTargetModuleAlias(targetModuleAlias);
        result.setTargetMetadataId(targetMetadataId);
        result.setTargetKeyField(targetKeyField);
        result.setTargetLabelField(targetLabelField);
        result.setCardinality(cardinality);
        result.setTargetUnavailablePolicy(targetUnavailablePolicy);
        result.setProjectionMappings(MetadataFieldReferenceConfig.encodeProjections(projectionMappings));
        return result;
    }

    public static MetadataFieldReferenceConfigDraft fromConfig(MetadataFieldReferenceConfig config) {
        if (config == null) return null;
        return new MetadataFieldReferenceConfigDraft(config.getTargetModuleAlias(), config.getTargetMetadataId(),
                config.getTargetKeyField(), config.getTargetLabelField(), config.getCardinality(),
                config.getTargetUnavailablePolicy(), MetadataFieldReferenceConfig.projectionMappings(config));
    }
}
