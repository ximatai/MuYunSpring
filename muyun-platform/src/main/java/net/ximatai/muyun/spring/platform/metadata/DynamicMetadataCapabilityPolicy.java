package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;

/** The deliberately small, dynamically mutable capability catalog. */
public final class DynamicMetadataCapabilityPolicy {
    private DynamicMetadataCapabilityPolicy() {
    }

    public static boolean supports(EntityCapability capability) {
        return MetadataCapabilityCatalog.isMutableInFirstRelease(capability);
    }

    public static Set<EntityCapability> declarations(Metadata metadata) {
        return MetadataCapabilityCatalog.declarations(metadata);
    }

    public static Set<String> declarationNames(Set<EntityCapability> capabilities) {
        return MetadataCapabilityCatalog.declarationNames(capabilities);
    }

    public static String requireSupportedDeclaration(String value) {
        return MetadataCapabilityCatalog.requireDeclaration(value);
    }
}
