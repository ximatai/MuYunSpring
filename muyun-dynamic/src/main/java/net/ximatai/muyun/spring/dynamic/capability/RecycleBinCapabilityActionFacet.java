package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.List;
import java.util.Optional;

/** Endpoint/OpenAPI facts and static operation shape owned by RECYCLE_BIN. */
public final class RecycleBinCapabilityActionFacet implements CapabilityActionContribution {
    private static final List<PlatformAction> STANDARD_ACTIONS = List.of(
            PlatformAction.RECYCLE_BIN_QUERY, PlatformAction.RECYCLE_BIN_RESTORE, PlatformAction.RECYCLE_BIN_PURGE);

    @Override
    public EntityCapability capability() {
        return EntityCapability.RECYCLE_BIN;
    }

    @Override
    public List<PlatformAction> standardActions() {
        return STANDARD_ACTIONS;
    }

    /** Purge stays an explicit static-service opt-in; dynamic declaration opts in as a complete lifecycle. */
    public List<PlatformOperationDefinition> staticOperations(boolean purgeEnabled) {
        var operations = new java.util.ArrayList<PlatformOperationDefinition>();
        operations.add(new PlatformOperationDefinition("recycleBin", "query", PlatformAction.RECYCLE_BIN_QUERY));
        operations.add(new PlatformOperationDefinition("recycleBin", "view", PlatformAction.RECYCLE_BIN_QUERY));
        operations.add(new PlatformOperationDefinition("recycleBin", "restore", PlatformAction.RECYCLE_BIN_RESTORE));
        if (purgeEnabled) {
            operations.add(new PlatformOperationDefinition("recycleBin", "purge", PlatformAction.RECYCLE_BIN_PURGE));
        }
        return List.copyOf(operations);
    }

    /** Dynamic declaration is a complete lifecycle opt-in, including explicit irreversible cleanup. */
    @Override
    public List<CapabilityHttpEndpointContract> dynamicHttpEndpoints() {
        return List.of(
                endpoint(PlatformAction.RECYCLE_BIN_QUERY, "recycleBinQuery", "POST", "/recycle-bin/query",
                        "WebQueryRequest", "RecycleBinItemPage"),
                endpoint(PlatformAction.RECYCLE_BIN_QUERY, "recycleBinView", "GET", "/recycle-bin/view/{id}",
                        null, "RecycleBinItem"),
                endpoint(PlatformAction.RECYCLE_BIN_RESTORE, "recycleBinRestore", "POST",
                        "/recycle-bin/{sourceDeleteOperationId}/restore", null, "RestoreReport"),
                endpoint(PlatformAction.RECYCLE_BIN_PURGE, "recycleBinPurge", "POST",
                        "/recycle-bin/{sourceDeleteOperationId}/purge", null, "PurgeReport"));
    }

    @Override
    public boolean isHttpOnlyDynamicAction(PlatformAction action) {
        return STANDARD_ACTIONS.contains(action);
    }

    private CapabilityHttpEndpointContract endpoint(PlatformAction action, String operationCode,
                                                     String method, String path, String requestSchema,
                                                     String responseSchema) {
        return new CapabilityHttpEndpointContract(action,
                new CapabilityEndpointProjection(operationCode, method, path), requestSchema, responseSchema);
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) {
        return Optional.empty();
    }

    @Override
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformOperationDefinition operation) {
        return switch (operation.action()) {
            case RECYCLE_BIN_QUERY -> switch (operation.operationCode()) {
                case "query" -> Optional.of(new CapabilityEndpointProjection("query", "POST", "/recycle-bin/query"));
                case "view" -> Optional.of(new CapabilityEndpointProjection("view", "GET", "/recycle-bin/view/{id}"));
                default -> Optional.empty();
            };
            case RECYCLE_BIN_RESTORE -> "restore".equals(operation.operationCode())
                    ? Optional.of(new CapabilityEndpointProjection("restore", "POST",
                    "/recycle-bin/{sourceDeleteOperationId}/restore")) : Optional.empty();
            case RECYCLE_BIN_PURGE -> "purge".equals(operation.operationCode())
                    ? Optional.of(new CapabilityEndpointProjection("purge", "POST",
                    "/recycle-bin/{sourceDeleteOperationId}/purge")) : Optional.empty();
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        return switch (action) {
            case RECYCLE_BIN_QUERY -> Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.WEB_QUERY,
                    "WebQueryRequest", "RecycleBinItemPage"));
            case RECYCLE_BIN_RESTORE -> Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.NONE, null, "RestoreReport"));
            case RECYCLE_BIN_PURGE -> Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.NONE, null, "PurgeReport"));
            default -> Optional.empty();
        };
    }
}
