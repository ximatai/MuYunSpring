package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.dynamic.capability.TreeCapabilityModule;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;

/** HTTP bridge owned by TREE for converting the shared SORT endpoint into tree placement. */
final class TreeCapabilityWebActionAdapter {
    private TreeCapabilityWebActionAdapter() {
    }

    static boolean supports(DynamicEntityOperations operations) {
        return tree().actions().enabledOnDynamicCapabilities(operations.describe().capabilities());
    }

    static int moveInTree(DynamicEntityOperations operations, String id, TreeSortWebRequest request) {
        if (!supports(operations)) {
            throw new PlatformException("dynamic entity does not support capability: " + EntityCapability.TREE);
        }
        TreeSortWebRequest normalized = request == null ? new TreeSortWebRequest(null, null, null) : request;
        if (!hasText(normalized.previousId()) && !hasText(normalized.nextId()) && !hasText(normalized.parentId())) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
        operations.moveInTree(id, normalized.previousId(), normalized.nextId(), normalized.parentId());
        return 1;
    }

    private static TreeCapabilityModule tree() {
        return CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.TREE, TreeCapabilityModule.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
