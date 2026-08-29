package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;

/** Effective capability state, including the explicit legacy-only inference boundary. */
public record MetadataCapabilityResolution(
        boolean legacyFieldInference,
        Set<EntityCapability> capabilities,
        MetadataCapabilityPlan plan
) {
}
