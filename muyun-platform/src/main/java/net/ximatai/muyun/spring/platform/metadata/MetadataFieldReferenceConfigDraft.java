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
        List<String> projectionMappings,
        boolean requireEnabled,
        List<String> affectMappings
) {
    public MetadataFieldReferenceConfigDraft(String targetModuleAlias, String targetMetadataId, String targetKeyField,
            String targetLabelField, ReferenceCardinality cardinality, ReferenceTargetUnavailablePolicy targetUnavailablePolicy,
            List<String> projectionMappings, boolean requireEnabled) {
        this(targetModuleAlias, targetMetadataId, targetKeyField, targetLabelField, cardinality,
                targetUnavailablePolicy, projectionMappings, requireEnabled, List.of());
    }

    public MetadataFieldReferenceConfigDraft {
        affectMappings = affectMappings == null ? List.of() : List.copyOf(affectMappings);
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
        result.setRequireEnabled(requireEnabled);
        result.setProjectionMappings(MetadataFieldReferenceConfig.encodeProjections(projectionMappings));
        result.setAffectMappings(MetadataFieldReferenceConfig.encodeProjections(affectMappings));
        return result;
    }

    public static MetadataFieldReferenceConfigDraft fromConfig(MetadataFieldReferenceConfig config) {
        if (config == null) return null;
        return new MetadataFieldReferenceConfigDraft(config.getTargetModuleAlias(), config.getTargetMetadataId(),
                config.getTargetKeyField(), config.getTargetLabelField(), config.getCardinality(),
                config.getTargetUnavailablePolicy(), MetadataFieldReferenceConfig.projectionMappings(config), Boolean.TRUE.equals(config.getRequireEnabled()), MetadataFieldReferenceConfig.affectMappings(config));
    }
}
