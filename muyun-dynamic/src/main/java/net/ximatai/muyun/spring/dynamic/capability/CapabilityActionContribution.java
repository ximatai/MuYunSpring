package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Optional;

/** Closed, source-neutral action contract for a registered platform capability. */
public interface CapabilityActionContribution {
    EntityCapability capability();

    List<PlatformAction> standardActions();

    Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action);

    record CapabilityEndpointProjection(String operationCode, String httpMethod, String path) {
    }
}
