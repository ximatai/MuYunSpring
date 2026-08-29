package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.List;
import java.util.Set;

/** Compiler-neutral field contribution plan for an effective metadata capability set. */
public record MetadataCapabilityPlan(
        Set<EntityCapability> capabilities,
        List<ModuleMetadataCapabilityFieldContribution> metadataFields,
        List<FieldDefinition> implicitRuntimeFields
) {
}
