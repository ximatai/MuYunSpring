package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.dynamic.capability.SortCapabilityActionFacet;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;

/** Source-side HTTP adapter for registered capability actions; controller methods stay endpoint-only. */
final class DynamicCapabilityWebActionAdapter {
    private DynamicCapabilityWebActionAdapter() {
    }

    static int sort(DynamicEntityOperations operations, String id, TreeSortWebRequest request) {
        CapabilityActionContribution owner = CapabilityModuleRegistry.defaultRegistry().actionOwner(PlatformAction.SORT)
                .orElseThrow(() -> new IllegalStateException("SORT capability action is not registered"));
        if (!(owner instanceof SortCapabilityActionFacet)) {
            throw new IllegalStateException("SORT capability action owner does not expose the SORT web contract");
        }
        if (!operations.describe().capabilities().contains(EntityCapability.SORT.name())) {
            throw new PlatformException("dynamic entity does not support capability: SORT");
        }
        TreeSortWebRequest normalized = request == null ? new TreeSortWebRequest(null, null, null) : request;
        if (TreeCapabilityWebActionAdapter.supports(operations)) {
            return TreeCapabilityWebActionAdapter.moveInTree(operations, id, normalized);
        }
        if (hasText(normalized.parentId())) {
            throw new IllegalArgumentException("sort parentId requires TREE capability");
        }
        if (hasText(normalized.previousId())) {
            operations.moveAfter(id, normalized.previousId());
            return 1;
        }
        if (hasText(normalized.nextId())) {
            operations.moveBefore(id, normalized.nextId());
            return 1;
        }
        throw new IllegalArgumentException("sort requires previousId or nextId");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
