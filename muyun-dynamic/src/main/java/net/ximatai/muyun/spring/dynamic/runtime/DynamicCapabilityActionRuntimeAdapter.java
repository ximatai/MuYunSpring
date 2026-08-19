package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityActionFacet;
import net.ximatai.muyun.spring.dynamic.capability.SortCapabilityActionFacet;

import java.util.List;

/** Source-side adapter registry. Adding a capability adds an adapter, not a central action switch. */
final class DynamicCapabilityActionRuntimeAdapter {
    private static final List<Handler> HANDLERS = List.of(new EnableHandler(), new SortHandler());

    private DynamicCapabilityActionRuntimeAdapter() {
    }

    static int execute(CapabilityActionContribution contribution,
                       PlatformAction action,
                       DynamicRecordService service,
                       String moduleAlias,
                       String entityAlias,
                       DynamicActionExecutionRequest request,
                       String traceId) {
        return HANDLERS.stream().filter(handler -> handler.supports(contribution)).findFirst()
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

    private interface Handler {
        boolean supports(CapabilityActionContribution contribution);

        int execute(PlatformAction action, DynamicRecordService service, String moduleAlias,
                    String entityAlias, DynamicActionExecutionRequest request, String traceId);
    }

    private static final class EnableHandler implements Handler {
        @Override
        public boolean supports(CapabilityActionContribution contribution) {
            return contribution instanceof EnableCapabilityActionFacet;
        }

        @Override
        public int execute(PlatformAction action, DynamicRecordService service, String moduleAlias,
                           String entityAlias, DynamicActionExecutionRequest request, String traceId) {
            String recordId = requireRecordId(request, action);
            return switch (action) {
                case ENABLE -> service.enableFromAction(moduleAlias, entityAlias, recordId, traceId);
                case DISABLE -> service.disableFromAction(moduleAlias, entityAlias, recordId, traceId);
                default -> throw new IllegalArgumentException("ENABLE runtime adapter does not own: " + action.code());
            };
        }
    }

    private static final class SortHandler implements Handler {
        @Override
        public boolean supports(CapabilityActionContribution contribution) {
            return contribution instanceof SortCapabilityActionFacet;
        }

        @Override
        public int execute(PlatformAction action, DynamicRecordService service, String moduleAlias,
                           String entityAlias, DynamicActionExecutionRequest request, String traceId) {
            if (action != PlatformAction.SORT) {
                throw new IllegalArgumentException("SORT runtime adapter does not own: " + action.code());
            }
            int intents = (request.orderedIds().isEmpty() ? 0 : 1)
                    + (hasText(request.beforeId()) ? 1 : 0) + (hasText(request.afterId()) ? 1 : 0);
            if (intents != 1) {
                throw new IllegalArgumentException("dynamic action requires exactly one sort intent: " + action.code());
            }
            if (!request.orderedIds().isEmpty()) {
                service.reorderFromAction(moduleAlias, entityAlias, request.orderedIds(), traceId);
                return 0;
            }
            String recordId = requireRecordId(request, action);
            if (hasText(request.beforeId())) {
                service.moveBeforeFromAction(moduleAlias, entityAlias, recordId, request.beforeId(), traceId);
                return 0;
            }
            service.moveAfterFromAction(moduleAlias, entityAlias, recordId, request.afterId(), traceId);
            return 0;
        }
    }

    private static String requireRecordId(DynamicActionExecutionRequest request, PlatformAction action) {
        if (request.recordId() != null && !request.recordId().isBlank()) {
            return request.recordId();
        }
        if (request.record() != null && request.record().getId() != null && !request.record().getId().isBlank()) {
            return request.record().getId();
        }
        throw new IllegalArgumentException("dynamic action requires record id: " + action.code());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
