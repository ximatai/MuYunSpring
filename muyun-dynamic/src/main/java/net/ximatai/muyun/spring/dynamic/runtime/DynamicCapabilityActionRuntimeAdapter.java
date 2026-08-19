package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityActionFacet;

import java.util.List;

/** Source-side adapter registry. Adding a capability adds an adapter, not a central action switch. */
final class DynamicCapabilityActionRuntimeAdapter {
    private static final List<Handler> HANDLERS = List.of(new EnableHandler());

    private DynamicCapabilityActionRuntimeAdapter() {
    }

    static int execute(CapabilityActionContribution contribution,
                       PlatformAction action,
                       DynamicRecordService service,
                       String moduleAlias,
                       String entityAlias,
                       String recordId,
                       String traceId) {
        return HANDLERS.stream().filter(handler -> handler.supports(contribution)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no dynamic runtime adapter for capability action: "
                        + action.code()))
                .execute(action, service, moduleAlias, entityAlias, recordId, traceId);
    }

    private interface Handler {
        boolean supports(CapabilityActionContribution contribution);

        int execute(PlatformAction action, DynamicRecordService service, String moduleAlias,
                    String entityAlias, String recordId, String traceId);
    }

    private static final class EnableHandler implements Handler {
        @Override
        public boolean supports(CapabilityActionContribution contribution) {
            return contribution instanceof EnableCapabilityActionFacet;
        }

        @Override
        public int execute(PlatformAction action, DynamicRecordService service, String moduleAlias,
                           String entityAlias, String recordId, String traceId) {
            return switch (action) {
                case ENABLE -> service.enableFromAction(moduleAlias, entityAlias, recordId, traceId);
                case DISABLE -> service.disableFromAction(moduleAlias, entityAlias, recordId, traceId);
                default -> throw new IllegalArgumentException("ENABLE runtime adapter does not own: " + action.code());
            };
        }
    }
}
