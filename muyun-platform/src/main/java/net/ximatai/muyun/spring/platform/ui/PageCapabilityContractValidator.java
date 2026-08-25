package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.Set;

/**
 * Source-neutral prerequisites for the standard page templates and traits.
 *
 * <p>The static Web declaration and the dynamic published layout have different source forms,
 * but neither may advertise a platform affordance whose module has no matching ability and
 * operation. Cross-module navigator source checks stay with their respective source resolvers.</p>
 */
public final class PageCapabilityContractValidator {
    public static final String TREE_MANAGEMENT = "TREE_MANAGEMENT";
    public static final String STANDARD_CRUD = "STANDARD_CRUD";
    public static final String ENABLED_STATUS = "ENABLED_STATUS";
    public static final String RECYCLE_BIN = "RECYCLE_BIN";

    private PageCapabilityContractValidator() {
    }

    public static void validate(String moduleAlias, String template, Set<String> traits,
                                Set<String> capabilityCodes, Set<String> actionCodes) {
        validate(moduleAlias, template, traits, capabilityCodes, actionCodes, false);
    }

    /**
     * A tree-management workbench may be backed by an aggregate tree resource rather than by the
     * page module's own record model. In that case the caller proves the resource's TREE contract
     * separately; requiring TREE from the host module would reject a valid scoped tree workspace.
     */
    public static void validate(String moduleAlias, String template, Set<String> traits,
                                Set<String> capabilityCodes, Set<String> actionCodes,
                                boolean treeResourceBacked) {
        Set<String> safeTraits = traits == null ? Set.of() : Set.copyOf(traits);
        Set<String> safeCapabilities = capabilityCodes == null ? Set.of() : Set.copyOf(capabilityCodes);
        Set<String> safeActions = actionCodes == null ? Set.of() : Set.copyOf(actionCodes);
        if (TREE_MANAGEMENT.equals(template) && !treeResourceBacked) {
            requireCapability(moduleAlias, TREE_MANAGEMENT, safeCapabilities, EntityCapability.TREE);
            requireActions(moduleAlias, TREE_MANAGEMENT, safeActions, PlatformAction.TREE);
        }
        if (safeTraits.contains(STANDARD_CRUD)) {
            requireActions(moduleAlias, STANDARD_CRUD, safeActions,
                    PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE);
        }
        if (safeTraits.contains(ENABLED_STATUS)) {
            requireCapability(moduleAlias, ENABLED_STATUS, safeCapabilities, EntityCapability.ENABLE);
            requireActions(moduleAlias, ENABLED_STATUS, safeActions, PlatformAction.ENABLE, PlatformAction.DISABLE);
        }
        if (safeTraits.contains(RECYCLE_BIN)) {
            requireCapability(moduleAlias, RECYCLE_BIN, safeCapabilities, EntityCapability.RECYCLE_BIN);
            requireActions(moduleAlias, RECYCLE_BIN, safeActions,
                    PlatformAction.RECYCLE_BIN_QUERY, PlatformAction.RECYCLE_BIN_RESTORE);
        }
    }

    private static void requireCapability(String moduleAlias, String consumer, Set<String> available,
                                          EntityCapability required) {
        if (!available.contains(required.name())) {
            throw new IllegalArgumentException("page capability is unavailable: module=" + moduleAlias
                    + ", consumer=" + consumer + ", required=" + required);
        }
    }

    private static void requireActions(String moduleAlias, String consumer, Set<String> available,
                                       PlatformAction... required) {
        // A descriptor without an action catalog is a partial model fact (for example, during
        // configuration validation before the runtime action compiler is installed). Its actions
        // must not be mistaken for an explicit empty action policy. Callers with a resolved
        // catalog always pass its actual codes and therefore get the strict contract below.
        if (available.isEmpty()) return;
        for (PlatformAction action : required) {
            if (!available.contains(action.code())) {
                throw new IllegalArgumentException("page action is unavailable: module=" + moduleAlias
                        + ", consumer=" + consumer + ", required=" + action.code());
            }
        }
    }
}
