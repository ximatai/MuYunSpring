package net.ximatai.muyun.spring.ability.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.EnumSet;
import java.util.Set;

/**
 * Neutral authority catalog for static capability declaration origins.
 *
 * <p>Baseline service abilities live here while registered capability modules contribute their
 * own policy. The scanner therefore never infers declaration authority from the capabilities of
 * the particular service currently being scanned.</p>
 */
public final class StaticCapabilityDeclarationCatalog {
    private static final Set<EntityCapability> BASE_SERVICE_ONLY = Set.copyOf(EnumSet.of(
            EntityCapability.CRUD,
            EntityCapability.SOFT_DELETE,
            EntityCapability.CACHE,
            EntityCapability.REFERENCE,
            EntityCapability.REFERENCE_DEPENDENCY,
            EntityCapability.DATA_SCOPE,
            EntityCapability.CHILD_RELATION));

    private StaticCapabilityDeclarationCatalog() {
    }

    public static boolean isServiceOnly(EntityCapability capability, StaticCapabilityRegistry registry) {
        if (BASE_SERVICE_ONLY.contains(capability)) {
            return true;
        }
        return registry.staticModules().stream()
                .filter(module -> module.capability() == capability)
                .anyMatch(module -> module.declarationPolicy() == StaticCapabilityDeclarationPolicy.SERVICE_ONLY);
    }
}
