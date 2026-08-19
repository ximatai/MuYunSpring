package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

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
