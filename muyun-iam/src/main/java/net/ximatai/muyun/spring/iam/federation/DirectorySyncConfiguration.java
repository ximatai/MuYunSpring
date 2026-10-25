package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.platform.job.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;

/** Immutable scope revisions keep queued work independent of later policy edits. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySyncConfiguration {
    public record Root(String externalDepartmentId, String organizationId) {}

    private final DirectorySyncStore store;
    private final IdentityProviderService providers;
    private final Map<String, ExternalDirectoryProtocol> protocols;
    private final OrganizationService organizations;
    private final PositionService positions;
    private final RoleService roles;
    private final ObjectProvider<JobService> jobs;

    public DirectorySyncConfiguration(
            DirectorySyncStore store,
            IdentityProviderService providers,
            List<ExternalDirectoryProtocol> protocols,
            OrganizationService organizations,
            PositionService positions,
            RoleService roles,
            ObjectProvider<JobService> jobs) {
        this.store = Objects.requireNonNull(store, "store");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.protocols =
                protocols.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ExternalDirectoryProtocol::providerType, p -> p));
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    static List<Root> roots(String text) {
        return text == null || text.isBlank()
                ? List.of()
                : List.of(DirectorySyncStore.decode(text, Root[].class));
    }

    ExternalDirectoryProtocol protocol(IdentityProviderDescriptor provider) {
        var protocol = protocols.get(provider.providerType());
        if (protocol == null) throw new IllegalArgumentException("Directory protocol unavailable");
        return protocol;
    }

    /** Must run before entering the CRUD mutation transaction: vendor I/O never holds database locks. */
    public void prepare(TenantIdentityPolicy policy) {
        if (policy.getSyncRoots() == null && !Boolean.TRUE.equals(policy.getSyncEnabled())) return;
        var provider =
                providers.descriptorOf(providers.findUsable(policy.getProviderId()).orElseThrow());
        var protocol = protocol(provider);
        var requested = roots(policy.getSyncRoots());
        if (requested.size() > 100)
            throw new IllegalArgumentException("At most 100 directory roots");
        Map<String, Root> unique = new LinkedHashMap<>();
        Map<String, ExternalDirectoryProtocol.Department> details = new HashMap<>();
        for (var root : requested) {
            if (root.externalDepartmentId() == null || root.organizationId() == null)
                throw new IllegalArgumentException("Root and organization required");
            var previous = unique.putIfAbsent(root.externalDepartmentId(), root);
            if (previous != null && !previous.organizationId().equals(root.organizationId()))
                throw new IllegalArgumentException("Same root maps to different organizations");
            var department = protocol.department(provider, root.externalDepartmentId());
            details.put(root.externalDepartmentId(), department);
            if (department.ancestorIds().isEmpty()
                    && !Boolean.TRUE.equals(policy.getSyncScopeConfirmed()))
                throw new IllegalArgumentException(
                        "Whole enterprise root requires explicit confirmation");
        }
        if (unique.isEmpty() && !Boolean.TRUE.equals(policy.getSyncScopeConfirmed()))
            throw new IllegalArgumentException("Empty scope requires explicit confirmation");
        List<Root> normalized =
                unique.values().stream()
                        .filter(
                                root ->
                                        details
                                                .get(root.externalDepartmentId())
                                                .ancestorIds()
                                                .stream()
                                                .noneMatch(unique::containsKey))
                        .sorted(Comparator.comparing(Root::externalDepartmentId))
                        .toList();
        policy.setSyncRoots(DirectorySyncStore.encode(normalized));
    }

    void maintain(TenantIdentityPolicy policy, TenantIdentityPolicy old) {
        if (policy.getSyncZoneId() != null && policy.getSyncZoneId().isBlank()) {
            policy.setSyncZoneId(null);
        }
        if (policy.getSyncZoneId() != null) ZoneId.of(policy.getSyncZoneId());
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
        if (old != null && !Objects.equals(old.getProviderId(), policy.getProviderId()))
            throw new IllegalArgumentException("Policy provider cannot change");
        policy.setSyncPlanId(old == null ? null : old.getSyncPlanId());
        policy.setSyncScopeId(old == null ? null : old.getSyncScopeId());
        policy.setPendingSyncScopeId(old == null ? null : old.getPendingSyncScopeId());
        if (old != null && old.getSyncScopeId() != null && policy.getSyncRoots() == null)
            throw new IllegalArgumentException("Use an explicitly confirmed empty scope to remove roots");
        var roots = roots(policy.getSyncRoots());
        if (old != null
                && (!Boolean.TRUE.equals(policy.getEnabled())
                        || !Boolean.TRUE.equals(policy.getSyncEnabled()))
                && Objects.equals(old.getSyncRoots(), policy.getSyncRoots())
                && Objects.equals(old.getSyncPositionId(), policy.getSyncPositionId())
                && Objects.equals(old.getSyncRoleId(), policy.getSyncRoleId())) {
            deletePlan(policy);
            return;
        }
        if (policy.getSyncRoots() != null) {
            roots.forEach(
                    root ->
                            organizations.requireEnabled(
                                    root.organizationId(),
                                    "Directory root organization unavailable"));
            positions.requireEnabled(
                    policy.getSyncPositionId(), "Default directory position required");
            if (policy.getSyncRoleId() != null) validateRole(policy.getSyncRoleId());
            var active = policy.getSyncScopeId() == null ? null : store.scopes.findById(policy.getSyncScopeId());
            boolean changed = active == null || !matches(active, policy);
            policy.setPendingSyncScopeId(old == null ? null : old.getPendingSyncScopeId());
            if (changed) {
                var pending = policy.getPendingSyncScopeId() == null ? null : store.scopes.findById(policy.getPendingSyncScopeId());
                if (pending == null || !matches(pending, policy)) {
                    var scope = new DirectorySyncScope();
                    scope.setTenantId(policy.getTenantId());
                    scope.setPolicyId(policy.getId());
                    scope.setProviderId(policy.getProviderId());
                    scope.setRoots(policy.getSyncRoots());
                    scope.setPositionId(policy.getSyncPositionId());
                    scope.setRoleId(policy.getSyncRoleId());
                    scope.setBaseScopeId(policy.getSyncScopeId());
                    DirectorySyncStore.insert(store.scopes, scope);
                    if (active != null && !Objects.equals(active.getRoots(), scope.getRoots()))
                        policy.setPendingSyncScopeId(scope.getId());
                    else {
                        policy.setSyncScopeId(scope.getId());
                        policy.setPendingSyncScopeId(null);
                    }
                }
            } else policy.setPendingSyncScopeId(null);
        }
        registerPlan(policy);
    }

    private boolean matches(DirectorySyncScope scope, TenantIdentityPolicy policy) {
        return Objects.equals(scope.getRoots(), policy.getSyncRoots())
                && Objects.equals(scope.getPositionId(), policy.getSyncPositionId())
                && Objects.equals(scope.getRoleId(), policy.getSyncRoleId());
    }

    private void registerPlan(TenantIdentityPolicy policy) {
        var active = policy.getSyncScopeId() == null ? null : store.scopes.findById(policy.getSyncScopeId());
        if (!Boolean.TRUE.equals(policy.getEnabled())
                || !Boolean.TRUE.equals(policy.getSyncEnabled())
                || active == null || roots(active.getRoots()).isEmpty()) {
            deletePlan(policy);
            return;
        }
        var plan = new JobPlan();
        plan.setId(policy.getSyncPlanId());
        plan.setTenantId(policy.getTenantId());
        plan.setObjectKey(policy.getId());
        plan.setJobType(DirectorySyncJob.TYPE);
        plan.setIdentityMode("SYSTEM");
        plan.setEnabled(true);
        JobSchedule.cron(policy.getSyncExpression(), policy.getSyncZoneId()).apply(plan);
        policy.setSyncPlanId(
                jobs.getObject()
                        .registerManaged(
                                TenantIdentityPolicyService.MODULE_ALIAS,
                                plan,
                                input(policy, "SYNC"))
                        .getId());
    }

    void requireActiveScope(DirectorySyncScope scope) {
        var policy = storePolicy(scope);
        if (policy == null || !Objects.equals(policy.getSyncScopeId(), scope.getId()))
            throw new JobFailure("DIRECTORY_SCOPE_CHANGED", false);
    }

    /** Missing detection is safe only for the active, fully confirmed revision. */
    boolean canFinalize(DirectorySyncScope scope) {
        var policy = storePolicy(scope);
        return policy != null && Boolean.TRUE.equals(policy.getEnabled())
                && Objects.equals(policy.getSyncScopeId(), scope.getId())
                && policy.getPendingSyncScopeId() == null;
    }

    private TenantIdentityPolicy storePolicy(DirectorySyncScope scope) {
        if (scope == null) return null;
        var policy = store.policy(scope.getPolicyId());
        return policy != null && Objects.equals(scope.getTenantId(), policy.getTenantId()) ? policy : null;
    }

    /** Called by the existing preview job in its fenced final transaction. */
    void completeScopePreview(DirectorySyncRun run) {
        if (!"SCOPE_PREVIEW".equals(run.getMode()) || !Boolean.TRUE.equals(run.getCompleted())) return;
        var scope = store.scopes.findById(run.getScopeId());
        var policy = storePolicy(scope);
        if (policy == null || !Objects.equals(policy.getPendingSyncScopeId(), scope.getId())
                || !Objects.equals(policy.getSyncScopeId(), scope.getBaseScopeId())) return;
        scope.setImpactReport(DirectorySyncStore.encode(impact(run, scope)));
        scope.setReportRunId(run.getId());
        DirectorySyncStore.save(store.scopes, scope);
    }

    void startScopePreview(TenantIdentityPolicy policy) {
        if (policy.getPendingSyncScopeId() == null) return;
        var scope = store.scopes.findById(policy.getPendingSyncScopeId());
        scope.setImpactReport(null);
        scope.setReportRunId(null);
        DirectorySyncStore.save(store.scopes, scope);
    }

    Map<String, Object> impactReport(TenantIdentityPolicy policy) {
        var pending = policy.getPendingSyncScopeId() == null ? null : store.scopes.findById(policy.getPendingSyncScopeId());
        if (pending == null || pending.getImpactReport() == null) return Map.of();
        return Map.of("scopeId", pending.getId(), "baseScopeId", pending.getBaseScopeId(),
                "runId", pending.getReportRunId(), "counts", DirectorySyncStore.decode(pending.getImpactReport(), Map.class));
    }

    void lock(TenantIdentityPolicy policy) {
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
    }

    void confirmScope(TenantIdentityPolicy policy, String expectedScopeId, String expectedRunId) {
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
        if (expectedScopeId == null || expectedRunId == null
                || !Objects.equals(expectedScopeId, policy.getPendingSyncScopeId()))
            throw new IllegalArgumentException("Scope proposal changed; preview the current proposal");
        var pending = store.scopes.findById(expectedScopeId);
        var report = pending == null || pending.getReportRunId() == null ? null : store.runs.findById(pending.getReportRunId());
        if (pending == null || !Objects.equals(expectedRunId, pending.getReportRunId())
                || !Objects.equals(pending.getBaseScopeId(), policy.getSyncScopeId())
                || pending.getImpactReport() == null || report == null
                || !Boolean.TRUE.equals(report.getCompleted()) || !"SUCCEEDED".equals(report.getStatus())
                || Boolean.TRUE.equals(report.getRetentionStarted())
                || !Objects.equals(pending.getImpactReport(), DirectorySyncStore.encode(impact(report, pending))))
            throw new IllegalArgumentException("Complete a fresh successful scope preview before confirmation");
        policy.setSyncScopeId(pending.getId());
        policy.setPendingSyncScopeId(null);
        registerPlan(policy);
    }

    private Map<String, Long> impact(DirectorySyncRun run, DirectorySyncScope scope) {
        // Aggregate in PostgreSQL rather than loading an unbounded directory into JVM memory.
        // Only this tenant/provider's source facts participate; manual employment/grants are excluded.
        var result = store.database.query("""
                with observed as (
                    select external_department_id, organization_id, root_id from iam_directory_sync_department
                    where tenant_id = ? and run_id = ? and deleted = false
                ), managed as (
                    select m.external_department_id, m.organization_id, m.root_id from iam_directory_department_mapping m
                    join iam_directory_sync_scope s on s.id = ? and s.tenant_id = m.tenant_id
                    cross join lateral jsonb_to_recordset(s.roots::jsonb)
                      as root("externalDepartmentId" text, "organizationId" text)
                    where m.tenant_id = ? and m.provider_id = ? and m.deleted = false
                      and m.root_id = root."externalDepartmentId" and m.organization_id = root."organizationId"
                ), changed_departments as (
                    select coalesce(m.external_department_id, n.external_department_id) as external_department_id
                    from managed m full join observed n using (external_department_id)
                    where m.organization_id is distinct from n.organization_id
                       or m.root_id is distinct from n.root_id
                ), placements as (
                    select seen.external_user_id, a.employee_id, d.external_department_id, d.organization_id,
                      first_value(d.external_department_id) over (partition by seen.external_user_id
                        order by case when d.external_department_id = seen.primary_department_id then 0 else 1 end,
                        membership.ordinality) as primary_department_id
                    from iam_directory_sync_seen seen
                    cross join lateral jsonb_array_elements_text(coalesce(seen.department_ids, '[]')::jsonb) with ordinality membership(id, ordinality)
                    join observed d on d.external_department_id = membership.id
                    left join iam_external_identity i on i.tenant_id = seen.tenant_id
                      and i.external_user_id = seen.external_user_id and i.provider_id = ?
                      and i.deleted = false
                    left join iam_employee_account a on a.tenant_id = seen.tenant_id and a.user_id = i.user_id and a.deleted = false
                    where seen.tenant_id = ? and seen.run_id = ? and seen.source_status = 'active' and seen.deleted = false
                ), owned as (
                    select p.* from iam_employee_position p where p.tenant_id = ?
                      and p.source = 'directory' and p.source_provider_id = ? and p.source_status = 'active' and p.deleted = false
                ), retiring as (
                    select p.* from owned p where not exists (
                        select 1 from placements n where n.employee_id = p.employee_id
                          and n.external_department_id = p.external_department_id
                          and n.organization_id = p.organization_id and p.position_id = ?)
                ), retiring_grants as (
                    select g.id, p.employee_id from iam_employment_role_grant g join owned p on p.id = g.employee_position_id
                    where g.tenant_id = ? and g.source = 'directory' and g.source_provider_id = ? and g.deleted = false
                      and (exists (select 1 from retiring r where r.id = p.id) or g.role_id is distinct from ?
                        or not exists (select 1 from placements n where n.employee_id = p.employee_id
                          and n.primary_department_id = p.external_department_id))
                ), changed_people as (
                    select 'employee:' || employee_id as person from retiring
                    union
                    select 'employee:' || employee_id from retiring_grants
                    union
                    select coalesce('employee:' || n.employee_id, 'external:' || n.external_user_id)
                    from placements n join changed_departments d using (external_department_id)
                    union
                    select coalesce('employee:' || n.employee_id, 'external:' || n.external_user_id)
                    from placements n where not exists (
                        select 1 from owned p where p.employee_id = n.employee_id
                          and p.external_department_id = n.external_department_id
                          and p.organization_id = n.organization_id and p.position_id = ?)
                )
                select
                    (select count(*) from changed_departments) as departments,
                    (select count(*) from changed_people) as people,
                    (select count(*) from retiring) as positions,
                    (select count(*) from retiring_grants) as grants
                """, run.getTenantId(), run.getId(), scope.getBaseScopeId(), run.getTenantId(), scope.getProviderId(),
                scope.getProviderId(), run.getTenantId(), run.getId(), run.getTenantId(), scope.getProviderId(),
                scope.getPositionId(), run.getTenantId(), scope.getProviderId(), scope.getRoleId(), scope.getPositionId()).getFirst();
        var counts = new LinkedHashMap<String, Long>();
        for (String key : List.of("departments", "people", "positions", "grants"))
            counts.put(key, ((Number) result.get(key)).longValue());
        return counts;
    }

    void validateRole(String id) {
        var role = roles.requireEnabled(id, "Default directory role unavailable");
        if (role.getAssignmentType() != RoleAssignmentType.EMPLOYMENT
                || role.getRoleKind() != RoleKind.STANDARD
                || role.getSystemPurpose() != RoleSystemPurpose.NONE
                || Boolean.TRUE.equals(role.getSystemManaged()))
            throw new IllegalArgumentException(
                    "Default directory role must be a non-management standard employment role");
    }

    void deletePlan(TenantIdentityPolicy policy) {
        store.lock(Map.of("tenantId", policy.getTenantId(), "providerId", policy.getProviderId()));
        if (policy.getSyncPlanId() != null)
            jobs.getObject()
                    .deleteManaged(
                            TenantIdentityPolicyService.MODULE_ALIAS,
                            policy.getSyncPlanId(),
                            policy.getTenantId());
        policy.setSyncPlanId(null);
    }

    static Map<String, Object> input(TenantIdentityPolicy policy, String mode) {
        if (policy.getSyncScopeId() == null)
            throw new IllegalArgumentException("Directory scope must be configured");
        String scopeId = "SCOPE_PREVIEW".equals(mode) ? policy.getPendingSyncScopeId() : policy.getSyncScopeId();
        if (scopeId == null) throw new IllegalArgumentException("Directory scope unavailable");
        return Map.of(
                "policyId",
                policy.getId(),
                "scopeId",
                scopeId,
                "providerId",
                policy.getProviderId(),
                "tenantId",
                policy.getTenantId(),
                "mode",
                mode);
    }
}
