package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import net.ximatai.muyun.spring.common.i18n.TextReference;

/** A capability's effective state, rather than a separate persisted toggle. */
public record ModuleMetadataCapabilityFact(
        EntityCapability capability,
        boolean enabled,
        boolean configurable,
        String reason,
        List<String> fieldContributions,
        String defaultKind,
        String defaultDescription,
        @JsonIgnore Map<String, TextReference> textSources
) {
    public ModuleMetadataCapabilityFact {
        textSources = textSources == null ? Map.of() : Map.copyOf(textSources);
    }

    public ModuleMetadataCapabilityFact(EntityCapability capability, boolean enabled, boolean configurable,
            String reason, List<String> fields, String defaultKind, String defaultDescription) {
        this(capability, enabled, configurable, reason, fields, defaultKind, defaultDescription, Map.of());
    }

    public static ModuleMetadataCapabilityFact declared(EntityCapability capability, boolean enabled, boolean configurable,
            TextReference reason, List<String> fields, String defaultKind, TextReference description) {
        return new ModuleMetadataCapabilityFact(capability, enabled, configurable, reason.originalText(), fields,
                defaultKind, description.originalText(), Map.of("reason", reason, "defaultDescription", description));
    }

    /** The field-derived and specialized capabilities do not use metadata change-set declarations. */
    @com.fasterxml.jackson.annotation.JsonProperty
    public boolean changeSetConfigurable() {
        return configurable && MetadataCapabilityCatalog.isMutableInFirstRelease(capability);
    }
}
