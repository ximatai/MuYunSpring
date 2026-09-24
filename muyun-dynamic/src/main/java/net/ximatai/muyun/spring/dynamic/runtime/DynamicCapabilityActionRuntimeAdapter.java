package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.DynamicCapabilityActionExecution;
import java.util.List;

/** Binds the narrow capability execution surface to the shared mutation runtime. */
final class DynamicCapabilityActionRuntimeAdapter {
    private DynamicCapabilityActionRuntimeAdapter() {}

    static boolean supports(CapabilityActionContribution contribution) {
        return contribution.dynamicRuntimeHandler().isPresent();
    }

    static int execute(CapabilityActionContribution contribution, PlatformAction action,
                       DynamicRecordMutationRuntime mutations, String moduleAlias, String entityAlias,
                       DynamicActionExecutionRequest request, String traceId) {
        DynamicCapabilityActionExecution execution = new DynamicCapabilityActionExecution() {
            @Override public int enable(String id) {
                return mutations.enable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
            }
            @Override public int disable(String id) {
                return mutations.disable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
            }
            @Override public void reorder(List<String> ids) {
                mutations.reorder(moduleAlias, entityAlias, ids, RuntimeMutationSource.ACTION, traceId);
            }
            @Override public void moveBefore(String id, String beforeId) {
                mutations.moveBefore(moduleAlias, entityAlias, id, beforeId, RuntimeMutationSource.ACTION, traceId);
            }
            @Override public void moveAfter(String id, String afterId) {
                mutations.moveAfter(moduleAlias, entityAlias, id, afterId, RuntimeMutationSource.ACTION, traceId);
            }
        };
        return contribution.dynamicRuntimeHandler().orElseThrow(() ->
                new IllegalStateException("no dynamic runtime adapter for capability action: " + action.code()))
                .execute(action, execution, request);
    }
}
