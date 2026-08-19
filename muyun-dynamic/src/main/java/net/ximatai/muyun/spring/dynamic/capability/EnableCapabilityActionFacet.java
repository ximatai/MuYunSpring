package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

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

}
