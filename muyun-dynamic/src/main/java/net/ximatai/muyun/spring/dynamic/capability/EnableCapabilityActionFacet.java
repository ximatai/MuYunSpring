package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** Standard action contribution and execution dispatch owned by ENABLE. */
public final class EnableCapabilityActionFacet implements CapabilityActionContribution {
    private static final List<PlatformAction> STANDARD_ACTIONS = List.of(PlatformAction.ENABLE, PlatformAction.DISABLE);

    @Override
    public net.ximatai.muyun.spring.common.platform.EntityCapability capability() {
        return net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE;
    }

    @Override
    public List<PlatformAction> standardActions() {
        return STANDARD_ACTIONS;
    }

    public boolean owns(PlatformAction action) {
        return STANDARD_ACTIONS.contains(action);
    }

    @Override
    public Optional<DynamicCapabilityActionRuntimeHandler> dynamicRuntimeHandler() {
        return Optional.of(this::executeDynamic);
    }

    @Override
    public Optional<StaticCapabilityActionRuntimeHandler> staticRuntimeHandler() {
        return Optional.of((execution, action) -> execution.executeEnable(action));
    }

    private int executeDynamic(PlatformAction action, DynamicRecordService service, String moduleAlias,
                               String entityAlias, DynamicActionExecutionRequest request, String traceId) {
        String recordId = requireRecordId(request, action);
        return switch (action) {
            case ENABLE -> service.enableFromAction(moduleAlias, entityAlias, recordId, traceId);
            case DISABLE -> service.disableFromAction(moduleAlias, entityAlias, recordId, traceId);
            default -> throw new IllegalArgumentException("ENABLE runtime handler does not own: " + action.code());
        };
    }

    private String requireRecordId(DynamicActionExecutionRequest request, PlatformAction action) {
        if (request.recordId() != null && !request.recordId().isBlank()) return request.recordId();
        if (request.record() != null && request.record().getId() != null && !request.record().getId().isBlank()) {
            return request.record().getId();
        }
        throw new IllegalArgumentException("dynamic action requires record id: " + action.code());
    }

    public List<PlatformOperationDefinition> staticOperations(Map<PlatformAction, ?> declaredOperations) {
        return STANDARD_ACTIONS.stream()
                .filter(declaredOperations::containsKey)
                .map(action -> new PlatformOperationDefinition("enable", action.code(), action))
                .toList();
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) {
        return switch (action) {
            case ENABLE -> Optional.of(new CapabilityEndpointProjection("enable", "POST", "/enable/{id}"));
            case DISABLE -> Optional.of(new CapabilityEndpointProjection("disable", "POST", "/disable/{id}"));
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        return owns(action)
                ? Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.RECORD_ACTION,
                "RecordActionWebRequest", "integer"))
                : Optional.empty();
    }

}
