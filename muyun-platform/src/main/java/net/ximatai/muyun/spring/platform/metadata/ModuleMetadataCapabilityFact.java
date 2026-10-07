package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;

/** A capability's effective state, rather than a separate persisted toggle. */
public record ModuleMetadataCapabilityFact(
        EntityCapability capability,
        boolean enabled,
        boolean configurable,
        String reason,
        List<String> fieldContributions,
        String defaultKind,
        String defaultDescription
) {
    /** The field-derived and specialized capabilities do not use metadata change-set declarations. */
    @com.fasterxml.jackson.annotation.JsonProperty
    public boolean changeSetConfigurable() {
        return configurable && MetadataCapabilityCatalog.isMutableInFirstRelease(capability);
    }
}
