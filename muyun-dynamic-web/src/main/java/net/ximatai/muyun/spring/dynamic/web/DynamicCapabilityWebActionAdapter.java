package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.dynamic.capability.DynamicCapabilityWebActionExecution;
import net.ximatai.muyun.spring.dynamic.capability.DynamicCapabilityWebSortRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;

/** Source-side HTTP adapter for registered capability actions; controller methods stay endpoint-only. */
final class DynamicCapabilityWebActionAdapter {
    private DynamicCapabilityWebActionAdapter() {
    }

    static int sort(DynamicEntityOperations operations, String id, TreeSortWebRequest request) {
        DynamicEntityOperations target = operations;
        TreeSortWebRequest normalized = request == null ? new TreeSortWebRequest(null, null, null) : request;
        CapabilityActionContribution owner = CapabilityModuleRegistry.defaultRegistry()
                .dynamicWebActionOwner(PlatformAction.SORT, target.describe().capabilities())
                .orElseThrow(() -> new IllegalStateException("SORT capability action is not registered"));
        return owner.dynamicWebActionHandler()
                .orElseThrow(() -> new IllegalStateException("SORT capability action has no dynamic-web handler"))
                .execute(new DynamicCapabilityWebActionExecution() {
                    @Override
                    public boolean supports(net.ximatai.muyun.spring.common.platform.EntityCapability capability) {
                        return target.describe().capabilities().contains(capability.name());
                    }

                    @Override
                    public void moveBefore(String recordId, String beforeId) {
                        target.moveBefore(recordId, beforeId);
                    }

                    @Override
                    public void moveAfter(String recordId, String afterId) {
                        target.moveAfter(recordId, afterId);
                    }

                    @Override
                    public void moveInTree(String recordId, String previousId, String nextId, String parentId) {
                        target.moveInTree(recordId, previousId, nextId, parentId);
                    }
                }, PlatformAction.SORT,
                        new DynamicCapabilityWebSortRequest(id, normalized.previousId(), normalized.nextId(), normalized.parentId()));
    }
}
