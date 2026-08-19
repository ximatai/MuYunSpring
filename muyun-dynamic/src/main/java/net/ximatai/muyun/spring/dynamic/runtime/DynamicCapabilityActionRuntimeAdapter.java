package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
/** Delegates to the dynamic runtime facet contributed by the action owner. */
final class DynamicCapabilityActionRuntimeAdapter {
    private DynamicCapabilityActionRuntimeAdapter() {
    }

    static boolean supports(CapabilityActionContribution contribution) {
        return contribution.dynamicRuntimeHandler().isPresent();
    }

    static int execute(CapabilityActionContribution contribution,
                       PlatformAction action,
                       DynamicRecordService service,
                       String moduleAlias,
                       String entityAlias,
                       DynamicActionExecutionRequest request,
                       String traceId) {
        return contribution.dynamicRuntimeHandler()
                .orElseThrow(() -> new IllegalStateException("no dynamic runtime adapter for capability action: "
                        + action.code()))
                .execute(action, service, moduleAlias, entityAlias, request, traceId);
    }

    /** Compatibility overload for record actions while callers migrate to typed action requests. */
    static int execute(CapabilityActionContribution contribution,
                       PlatformAction action,
                       DynamicRecordService service,
                       String moduleAlias,
                       String entityAlias,
                       String recordId,
                       String traceId) {
        return execute(contribution, action, service, moduleAlias, entityAlias,
                DynamicActionExecutionRequest.id(recordId), traceId);
    }

}
