package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;

import java.util.List;
import java.util.Optional;

/** Standard action and endpoint facts owned by SORT. */
public final class SortCapabilityActionFacet implements CapabilityActionContribution {
    @Override
    public EntityCapability capability() {
        return EntityCapability.SORT;
    }

    @Override
    public List<PlatformAction> standardActions() {
        return List.of(PlatformAction.SORT);
    }

    public List<PlatformOperationDefinition> staticOperations() {
        return List.of(new PlatformOperationDefinition("sort", "sort", PlatformAction.SORT));
    }

    @Override
    public Optional<DynamicCapabilityActionRuntimeHandler> dynamicRuntimeHandler() {
        return Optional.of(this::executeDynamic);
    }

    @Override
    public Optional<DynamicCapabilityWebActionHandler> dynamicWebActionHandler() {
        return Optional.of(this::executeDynamicWeb);
    }

    private int executeDynamicWeb(DynamicCapabilityWebActionExecution execution, PlatformAction action,
                                  DynamicCapabilityWebSortRequest request) {
        if (action != PlatformAction.SORT) {
            throw new IllegalArgumentException("SORT dynamic-web handler does not own: " + action.code());
        }
        if (!execution.supports(EntityCapability.SORT)) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformException(
                    "dynamic entity does not support capability: SORT");
        }
        if (request.hasParentId()) {
            throw new IllegalArgumentException("sort parentId requires TREE capability");
        }
        if (request.hasPreviousId()) {
            execution.moveAfter(request.id(), request.previousId());
            return 1;
        }
        if (request.hasNextId()) {
            execution.moveBefore(request.id(), request.nextId());
            return 1;
        }
        throw new IllegalArgumentException("sort requires previousId or nextId");
    }

    @Override
    public Optional<StaticCapabilityActionRuntimeHandler> staticRuntimeHandler() {
        return Optional.of((execution, action) -> {
            if (action != PlatformAction.SORT) {
                throw new IllegalArgumentException("SORT static runtime handler does not own: " + action.code());
            }
            return execution.executeSort();
        });
    }

    private int executeDynamic(PlatformAction action, DynamicRecordService service, String moduleAlias,
                               String entityAlias, DynamicActionExecutionRequest request, String traceId) {
        if (action != PlatformAction.SORT) {
            throw new IllegalArgumentException("SORT runtime handler does not own: " + action.code());
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

    private String requireRecordId(DynamicActionExecutionRequest request, PlatformAction action) {
        if (request.recordId() != null && !request.recordId().isBlank()) return request.recordId();
        if (request.record() != null && request.record().getId() != null && !request.record().getId().isBlank()) {
            return request.record().getId();
        }
        throw new IllegalArgumentException("dynamic action requires record id: " + action.code());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** The TREE module owns placement semantics while deliberately reusing SORT's action contract. */
    public PlatformOperationDefinition treeBridgeOperation() {
        return new PlatformOperationDefinition("tree", "sort", PlatformAction.SORT);
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) {
        return action == PlatformAction.SORT
                ? Optional.of(new CapabilityEndpointProjection("sort", "POST", "/sort/{id}"))
                : Optional.empty();
    }

    @Override
    public Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        if (action != PlatformAction.SORT) {
            return Optional.empty();
        }
        return Optional.of(treeBridge
                ? new CapabilityWebActionContract(CapabilityWebRequestBody.TREE_SORT, "TreeSortWebRequest", "integer")
                : new CapabilityWebActionContract(CapabilityWebRequestBody.SORT, "SortWebRequest", "integer"));
    }
}
