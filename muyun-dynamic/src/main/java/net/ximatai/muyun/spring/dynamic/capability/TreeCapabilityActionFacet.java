package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Standard read actions owned by TREE. Tree placement uses SORT's explicit bridge contract. */
public final class TreeCapabilityActionFacet implements CapabilityActionContribution {
    private static final List<CapabilityEndpointProjection> WEB_ENDPOINTS = List.of(
            new CapabilityEndpointProjection("tree", "GET", "/tree"),
            new CapabilityEndpointProjection("subtree", "GET", "/tree/{id}"));
    @Override
    public EntityCapability capability() {
        return EntityCapability.TREE;
    }

    @Override
    public List<PlatformAction> standardActions() {
        return List.of(PlatformAction.TREE);
    }

    public List<PlatformOperationDefinition> staticOperations() {
        return List.of(
                new PlatformOperationDefinition("tree", "tree", PlatformAction.TREE),
                new PlatformOperationDefinition("tree", "treeQuery", PlatformAction.TREE),
                new PlatformOperationDefinition("tree", "subtree", PlatformAction.TREE),
                new PlatformOperationDefinition("tree", "sort", PlatformAction.SORT));
    }

    @Override
    public Optional<StaticCapabilityActionRuntimeHandler> staticRuntimeHandler() {
        return Optional.of((execution, action) -> execution.executeTree(action));
    }

    @Override
    public Optional<DynamicCapabilityWebActionHandler> dynamicWebActionHandler() {
        return Optional.of(this::executeDynamicWeb);
    }

    private int executeDynamicWeb(DynamicCapabilityWebActionExecution execution, PlatformAction action,
                                  DynamicCapabilityWebSortRequest request) {
        if (action != PlatformAction.SORT) {
            throw new IllegalArgumentException("TREE dynamic-web handler does not own: " + action.code());
        }
        if (!execution.supports(EntityCapability.TREE)) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformException(
                    "dynamic entity does not support capability: TREE");
        }
        if (!request.hasPlacementIntent()) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
        execution.moveInTree(request.id(), request.previousId(), request.nextId(), request.parentId());
        return 1;
    }

    /** Dynamic web/OpenAPI projection facts; tree query remains a static-host only operation. */
    public List<CapabilityEndpointProjection> webEndpointProjections() {
        return WEB_ENDPOINTS;
    }

    /** Source adapters use this typed facet instead of teaching SORT about hierarchical records. */
    public boolean enabledOnDynamicCapabilities(Set<String> capabilityNames) {
        return capabilityNames != null && capabilityNames.contains(capability().name());
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) {
        return Optional.empty();
    }

    @Override
    public Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        return action == PlatformAction.TREE
                ? Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.WEB_QUERY,
                "WebQueryRequest", "WebListResponse"))
                : Optional.empty();
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformOperationDefinition operation) {
        if (operation.action() != PlatformAction.TREE) return Optional.empty();
        return switch (operation.operationCode()) {
            case "tree" -> Optional.of(WEB_ENDPOINTS.getFirst());
            case "treeQuery" -> Optional.of(new CapabilityEndpointProjection("treeQuery", "POST", "/tree/query"));
            case "subtree" -> Optional.of(WEB_ENDPOINTS.get(1));
            default -> Optional.empty();
        };
    }
}
