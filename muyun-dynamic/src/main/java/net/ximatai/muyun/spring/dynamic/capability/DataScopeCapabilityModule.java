package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityDeclarationPolicy;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DataScopeCapabilityModule implements CapabilityModule, CapabilityActionContribution {
    public EntityCapability capability() { return EntityCapability.DATA_SCOPE; }
    public Set<EntityCapability> dependencies() { return Set.of(EntityCapability.CRUD); }
    public CapabilityActionContribution actionContribution() { return this; }
    public StaticCapabilityDeclarationPolicy declarationPolicy() { return StaticCapabilityDeclarationPolicy.SERVICE_ONLY; }
    public Optional<StaticCapabilityFacet> staticFacet() {
        return Optional.of(new StaticCapabilityFacet() {
            public boolean supports(Object service) {
                return service instanceof DataScopeAbility<?> scope && scope.modelClass() != null
                        && DataScopeCapable.class.isAssignableFrom(scope.modelClass())
                        && DataScopeFieldMapping.STANDARD.equals(scope.dataScopeFieldMapping());
            }
            public List<PlatformOperationDefinition> standardOperations(StaticCapabilityOperationContext context) {
                if (!supports(context.service())) return List.of();
                return List.of("permissions", "permissionCandidates", "managePermissions").stream()
                        .map(code -> new PlatformOperationDefinition("dataScope", code, PlatformAction.MANAGE_PERMISSIONS)).toList();
            }
        });
    }
    public List<PlatformAction> standardActions() { return List.of(PlatformAction.MANAGE_PERMISSIONS); }
    public Optional<StaticCapabilityActionRuntimeHandler> staticRuntimeHandler() {
        return Optional.of((execution, action) -> execution.executePermissions());
    }
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) { return Optional.empty(); }
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformOperationDefinition operation) {
        return dynamicHttpEndpoints().stream().map(CapabilityHttpEndpointContract::endpoint)
                .filter(endpoint -> endpoint.operationCode().equals(operation.operationCode())).findFirst();
    }
    public boolean includesRecordActionDescriptor(PlatformAction action) { return true; }
    public boolean isHttpOnlyDynamicAction(PlatformAction action) { return action == PlatformAction.MANAGE_PERMISSIONS; }
    public List<CapabilityHttpEndpointContract> dynamicHttpEndpoints() {
        return List.of(
            endpoint("permissions", "GET", "/permissions/{id}", null),
            endpoint("permissionCandidates", "GET", "/permissions/{id}/candidates", null),
            endpoint("managePermissions", "POST", "/permissions/{id}", "RecordPermissionChange"));
    }
    private CapabilityHttpEndpointContract endpoint(String code, String method, String path, String request) {
        return new CapabilityHttpEndpointContract(PlatformAction.MANAGE_PERMISSIONS,
                new CapabilityEndpointProjection(code, method, path), request, "object");
    }
    public Optional<CapabilityWebActionContract> webActionContract(PlatformAction action, boolean treeBridge) {
        return Optional.of(new CapabilityWebActionContract(CapabilityWebRequestBody.PERMISSIONS, "RecordPermissionChange", "object"));
    }
}
