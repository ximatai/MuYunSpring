package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;

import java.util.List;
import java.util.Optional;

/** Closed, source-neutral action contract for a registered platform capability. */
public interface CapabilityActionContribution {
    EntityCapability capability();

    List<PlatformAction> standardActions();

    Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action);

    /**
     * Operation-aware endpoint projection. Capabilities with more than one operation for a
     * single action (TREE) own those shapes here instead of extending a global endpoint switch.
     */
    default Optional<CapabilityEndpointProjection> endpointProjection(PlatformOperationDefinition operation) {
        return endpointProjection(operation.action()).filter(projection ->
                projection.operationCode().equals(operation.operationCode()));
    }

    /** Typed HTTP/OpenAPI facts; TREE may explicitly select a capability's bridge variant. */
    default Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        return Optional.empty();
    }

    /** Actions with endpoint-specific identity must not be shown by the generic record-action API. */
    default boolean isHttpOnlyDynamicAction(PlatformAction action) {
        return false;
    }

    /** Complete source-neutral HTTP facts for endpoints that do not fit generic record action payloads. */
    default List<CapabilityHttpEndpointContract> dynamicHttpEndpoints() {
        return List.of();
    }

    record CapabilityEndpointProjection(String operationCode, String httpMethod, String path) {
    }

    record CapabilityWebActionContract(CapabilityWebRequestBody requestBody,
                                       String openApiRequestSchema,
                                       String openApiResponseSchema) {
    }

    record CapabilityHttpEndpointContract(PlatformAction action,
                                          CapabilityEndpointProjection endpoint,
                                          String openApiRequestSchema,
                                          String openApiResponseSchema) {
    }

    enum CapabilityWebRequestBody {
        SORT,
        TREE_SORT,
        WEB_QUERY,
        NONE
    }
}
