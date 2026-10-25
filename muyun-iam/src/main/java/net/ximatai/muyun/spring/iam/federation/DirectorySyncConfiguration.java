package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Immutable scope revisions keep queued work independent of later policy edits. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySyncConfiguration {
    public record Root(String externalDepartmentId, String organizationId) { }
    private final DirectorySyncStore store;
    private final IdentityProviderService providers;
    private final Map<String, ExternalDirectoryProtocol> protocols;
    private final OrganizationService organizations;
    private final PositionService positions;
    private final RoleService roles;
    private final org.springframework.beans.factory.ObjectProvider<JobService> jobs;
    public DirectorySyncConfiguration(DirectorySyncStore store, IdentityProviderService providers,
            List<ExternalDirectoryProtocol> protocols, OrganizationService organizations, PositionService positions,
            RoleService roles, org.springframework.beans.factory.ObjectProvider<JobService> jobs) {
        this.store = java.util.Objects.requireNonNull(store, "store"); this.providers = java.util.Objects.requireNonNull(providers, "providers"); this.protocols = protocols.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ExternalDirectoryProtocol::providerType, p -> p));
        this.organizations = java.util.Objects.requireNonNull(organizations, "organizations"); this.positions = java.util.Objects.requireNonNull(positions, "positions"); this.roles = java.util.Objects.requireNonNull(roles, "roles"); this.jobs = java.util.Objects.requireNonNull(jobs, "jobs");
    }
    static List<Root> roots(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(DirectorySyncStore.decode(text, Root[].class));
    }
    ExternalDirectoryProtocol protocol(IdentityProviderDescriptor provider) {
        var protocol = protocols.get(provider.providerType());
        if (protocol == null) throw new IllegalArgumentException("Directory protocol unavailable"); return protocol;
    }
    /** Must run before entering the CRUD mutation transaction: vendor I/O never holds database locks. */
    public void prepare(TenantIdentityPolicy policy) {
        if (policy.getSyncRoots() == null && !Boolean.TRUE.equals(policy.getSyncEnabled())) return;
        var provider = providers.descriptorOf(providers.findUsable(policy.getProviderId()).orElseThrow());
        var protocol = protocol(provider);
        var requested = roots(policy.getSyncRoots());
        if (requested.size() > 100) throw new IllegalArgumentException("At most 100 directory roots");
        Map<String, Root> unique = new LinkedHashMap<>();
        Map<String, ExternalDirectoryProtocol.Department> details = new HashMap<>();
        for (var root : requested) {
            if (root.externalDepartmentId() == null || root.organizationId() == null) throw new IllegalArgumentException("Root and organization required");
            var previous = unique.putIfAbsent(root.externalDepartmentId(), root);
            if (previous != null && !previous.organizationId().equals(root.organizationId())) throw new IllegalArgumentException("Same root maps to different organizations");
            var department = protocol.department(provider, root.externalDepartmentId()); details.put(root.externalDepartmentId(), department);
            if (department.ancestorIds().isEmpty() && !Boolean.TRUE.equals(policy.getSyncScopeConfirmed())) throw new IllegalArgumentException("Whole enterprise root requires explicit confirmation");
        }
        if (unique.isEmpty() && !Boolean.TRUE.equals(policy.getSyncScopeConfirmed())) throw new IllegalArgumentException("Empty scope requires explicit confirmation");
        List<Root> normalized = unique.values().stream().filter(root -> details.get(root.externalDepartmentId()).ancestorIds().stream().noneMatch(unique::containsKey)).toList();
        policy.setSyncRoots(DirectorySyncStore.encode(normalized));
    }
    void maintain(TenantIdentityPolicy policy, TenantIdentityPolicy old) {
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
        if (old != null && !Objects.equals(old.getProviderId(), policy.getProviderId())) throw new IllegalArgumentException("Policy provider cannot change");
        policy.setSyncPlanId(old == null ? null : old.getSyncPlanId());
        policy.setSyncScopeId(old == null ? null : old.getSyncScopeId());
        var roots = roots(policy.getSyncRoots());
        if (old != null && (!Boolean.TRUE.equals(policy.getEnabled()) || !Boolean.TRUE.equals(policy.getSyncEnabled()))
                && Objects.equals(old.getSyncRoots(), policy.getSyncRoots())
                && Objects.equals(old.getSyncPositionId(), policy.getSyncPositionId()) && Objects.equals(old.getSyncRoleId(), policy.getSyncRoleId())) {
            deletePlan(policy); return;
        }
        if (policy.getSyncRoots() != null) {
            roots.forEach(root -> organizations.requireEnabled(root.organizationId(), "Directory root organization unavailable"));
            positions.requireEnabled(policy.getSyncPositionId(), "Default directory position required");
            if (policy.getSyncRoleId() != null) validateRole(policy.getSyncRoleId());
            boolean changed = old == null || !Objects.equals(old.getSyncRoots(), policy.getSyncRoots())
                    || !Objects.equals(old.getSyncPositionId(), policy.getSyncPositionId()) || !Objects.equals(old.getSyncRoleId(), policy.getSyncRoleId());
            if (changed) {
                var scope = new DirectorySyncScope(); scope.setTenantId(policy.getTenantId()); scope.setPolicyId(policy.getId()); scope.setProviderId(policy.getProviderId());
                scope.setRoots(policy.getSyncRoots()); scope.setPositionId(policy.getSyncPositionId()); scope.setRoleId(policy.getSyncRoleId());
                DirectorySyncStore.insert(store.scopes, scope); policy.setSyncScopeId(scope.getId());
            }
        }
        if (policy.getSyncRoots() == null) policy.setSyncScopeId(null);
        if (!Boolean.TRUE.equals(policy.getEnabled()) || !Boolean.TRUE.equals(policy.getSyncEnabled()) || roots.isEmpty()) {
            deletePlan(policy); return;
        }
        var plan = new JobPlan(); plan.setId(policy.getSyncPlanId()); plan.setTenantId(policy.getTenantId()); plan.setObjectKey(policy.getId());
        plan.setJobType(DirectorySyncJob.TYPE); plan.setIdentityMode("SYSTEM"); plan.setEnabled(true);
        JobSchedule.cron(policy.getSyncExpression(), "UTC").apply(plan);
        policy.setSyncPlanId(jobs.getObject().registerManaged(TenantIdentityPolicyService.MODULE_ALIAS, plan, input(policy, "SYNC")).getId());
    }
    void validateRole(String id) {
        var role = roles.requireEnabled(id, "Default directory role unavailable");
        if (role.getAssignmentType() != RoleAssignmentType.EMPLOYMENT || role.getRoleKind() != RoleKind.STANDARD
                || role.getSystemPurpose() != RoleSystemPurpose.NONE || Boolean.TRUE.equals(role.getSystemManaged()))
            throw new IllegalArgumentException("Default directory role must be a non-management standard employment role");
    }
    void deletePlan(TenantIdentityPolicy policy) {
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
        if (policy.getSyncPlanId() != null) jobs.getObject().deleteManaged(TenantIdentityPolicyService.MODULE_ALIAS, policy.getSyncPlanId(), policy.getTenantId());
        policy.setSyncPlanId(null);
    }
    static Map<String, Object> input(TenantIdentityPolicy policy, String mode) {
        if (policy.getSyncScopeId() == null) throw new IllegalArgumentException("Directory scope must be configured");
        return Map.of("policyId", policy.getId(), "scopeId", policy.getSyncScopeId(), "providerId", policy.getProviderId(), "tenantId", policy.getTenantId(), "mode", mode);
    }
}
