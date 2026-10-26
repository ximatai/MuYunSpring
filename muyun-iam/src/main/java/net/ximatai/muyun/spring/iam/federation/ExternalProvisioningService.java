package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Authoritative admission and the shared atomic account writer. Provider I/O precedes admission locks. */
@Service
@ConditionalOnExternalIdentityFederation
public class ExternalProvisioningService {
    private final DirectorySyncConfiguration directory;
    private final DirectorySyncStore store;
    private final DirectorySyncWriter writer;
    private final IdentityProviderService providers;
    private final TenantIdentityPolicyService policies;
    private final ExternalIdentityService identities;
    private final ExternalIdentityAdmissionLocks locks;
    private final JoinApplicationDao applications;
    private final net.ximatai.muyun.spring.iam.department.DepartmentService departments;
    private final net.ximatai.muyun.spring.iam.organization.OrganizationService organizations;

    ExternalProvisioningService(DirectorySyncConfiguration directory, DirectorySyncStore store,
            DirectorySyncWriter writer, IdentityProviderService providers, TenantIdentityPolicyService policies,
            ExternalIdentityService identities, ExternalIdentityAdmissionLocks locks, JoinApplicationDao applications,
            net.ximatai.muyun.spring.iam.department.DepartmentService departments,
            net.ximatai.muyun.spring.iam.organization.OrganizationService organizations) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.store = Objects.requireNonNull(store, "store");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.departments = Objects.requireNonNull(departments, "departments");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
    }

    record Observation(ExternalDirectoryProtocol.Person person, Map<String, List<String>> ancestors, Instant at) { }

    /** Null means directory-only evidence must be read again inside the source transaction. */
    Observation observe(IdentityProviderDescriptor provider, String externalId) {
        try {
            var protocol = directory.protocol(provider);
            if (!protocol.supportsPersonLookup(provider)) return null;
            Instant observedAt = Instant.now();
            var person = protocol.person(provider, externalId);
            if (person == null || !externalId.equals(person.id()) || !"active".equals(person.status())) throw rejected();
            Map<String, List<String>> ancestors = new HashMap<>();
            for (String id : person.departmentIds())
                ancestors.put(id, DirectorySyncConfiguration.ancestors(protocol, provider, id));
            return new Observation(person, Map.copyOf(ancestors), observedAt);
        } catch (RuntimeException failure) { throw rejected(); }
    }

    record Admission(ExternalDirectoryProtocol.Person person, List<DirectoryDepartmentMapping> memberships, Instant checkedAt, String primarySource) { }

    /** Caller owns the tenant/source and configuration locks; browser organization fields never enter here. */
    Admission admit(TenantIdentityPolicy policy, IdentityProviderDescriptor provider, String externalId, String enterprise, Observation observation) {
        var roots = policy.getAdmissionRoots() == null || policy.getAdmissionRoots().isBlank()
                ? List.<String>of() : List.of(DirectorySyncStore.decode(policy.getAdmissionRoots(), String[].class));
        var decision = application(policy.getTenantId(), provider.id(), enterprise, externalId);
        if (decision != null && decision.getStatus() == JoinApplicationStatus.REJECTED) throw rejected();
        if (policy.getSyncScopeId() == null || roots.isEmpty() || identities.isAutoRebindBlocked(provider.id(), externalId)) throw rejected();
        if (observation != null && !store.database.query(
                "select s.id from iam_directory_sync_seen s join iam_directory_sync_run r on r.id=s.run_id "
                + "join iam_directory_sync_scope c on c.id=r.scope_id where s.tenant_id=? and c.tenant_id=? "
                + "and c.provider_id=? and s.external_user_id=? and s.source_status <> 'active' "
                + "and s.created_at >= ? limit 1", policy.getTenantId(), policy.getTenantId(), provider.id(), externalId,
                java.sql.Timestamp.from(observation.at())).isEmpty()) throw rejected();
        var person = observation == null ? null : observation.person();
        Instant checked = observation == null ? null : observation.at();
        String primarySource = person != null && person.primaryDepartmentId() != null ? "vendor" : "inferred";
        if (observation == null) {
            // Round order is immutable: continuation must not promote old observations.
            // The latest round must be complete; absence in that round fails closed.
            var runs = store.runs.query(Criteria.of().eq("tenantId", policy.getTenantId())
                    .eq("policyId", policy.getId()).eq("scopeId", policy.getSyncScopeId())
                    .in("mode", List.of("SYNC", "PREVIEW")), new PageRequest(0, 1), Sort.desc("createdAt"), Sort.desc("id"));
            if (runs.isEmpty() || !Boolean.TRUE.equals(runs.getFirst().getCompleted())
                    || !Objects.equals(runs.getFirst().getAuthorityFingerprint(), provider.authorityFingerprint())
                    || Boolean.TRUE.equals(runs.getFirst().getRetentionStarted())) throw rejected();
            var seen = store.seen.query(Criteria.of().eq("tenantId", policy.getTenantId())
                    .eq("runId", runs.getFirst().getId()).eq("externalUserId", externalId), new PageRequest(0, 1));
            if (seen.isEmpty()) throw rejected();
            var fact = seen.getFirst();
            person = DirectorySyncStore.decode(fact.getPerson(), ExternalDirectoryProtocol.Person.class)
                    .withMemberships(fact.getPrimaryDepartmentId(), List.of(DirectorySyncStore.decode(fact.getDepartmentIds(), String[].class)));
            if (!"active".equals(fact.getSourceStatus())) throw rejected();
            checked = fact.getCreatedAt();
            primarySource = fact.getPrimaryDepartmentSource();
        }
        if (person == null || !externalId.equals(person.id()) || !"active".equals(person.status())
                || checked == null || !Instant.now().isBefore(checked.plusSeconds(policy.getSourceStatusMaxAgeSeconds()))) throw rejected();
        var memberships = new ArrayList<DirectoryDepartmentMapping>();
        for (String id : new LinkedHashSet<>(person.departmentIds())) {
            var map = store.mapping(policy.getTenantId(), provider.id(), id);
            if (!authoritativeMapping(policy, provider, map)) continue;
            boolean allowed = roots.contains(id);
            if (observation != null) allowed |= observation.ancestors().getOrDefault(id, List.of()).stream().anyMatch(roots::contains);
            else {
                var visited = new HashSet<String>();
                var current = map;
                while (current != null && visited.add(current.getExternalDepartmentId()) && visited.size() <= 256) {
                    if (!authoritativeMapping(policy, provider, current)) throw rejected();
                    if (current.getLastSeenAt() == null || !Instant.now().isBefore(current.getLastSeenAt()
                            .plusSeconds(policy.getSourceStatusMaxAgeSeconds()))) throw rejected();
                    allowed |= roots.contains(current.getExternalDepartmentId());
                    current = current.getParentExternalId() == null ? null
                            : store.mapping(policy.getTenantId(), provider.id(), current.getParentExternalId());
                }
            }
            if (allowed) memberships.add(map);
        }
        if (memberships.isEmpty()) throw rejected();
        var primary = person.primaryDepartmentId();
        if (memberships.stream().noneMatch(m -> m.getExternalDepartmentId().equals(primary))) primarySource = "inferred";
        return new Admission(person, List.copyOf(memberships), checked, primarySource);
    }

    ExternalLoginResults.Confirmation confirmation(TenantIdentityPolicy policy, Admission admission) {
        var placements = admission.memberships().stream().map(mapping -> {
            var organization = organizations.selectActiveRaw(mapping.getOrganizationId());
            var department = departments.selectActiveRaw(mapping.getDepartmentId());
            if (organization == null || department == null) throw rejected();
            return organization.getTitle() + " / " + department.getTitle();
        }).toList();
        return new ExternalLoginResults.Confirmation(admission.person().title(), policy.getTenantId(),
                admission.memberships().stream().map(DirectoryDepartmentMapping::getExternalDepartmentId).toList(), placements);
    }

    private boolean authoritativeMapping(TenantIdentityPolicy policy, IdentityProviderDescriptor provider,
            DirectoryDepartmentMapping mapping) {
        if (mapping == null || policy.getSyncScopeId() == null) return false;
        var run = store.runs.findById(mapping.getLastRunId());
        var scope = store.scopes.findById(policy.getSyncScopeId());
        return run != null && scope != null && Objects.equals(run.getTenantId(), policy.getTenantId())
                && Objects.equals(run.getScopeId(), policy.getSyncScopeId())
                && "SYNC".equals(run.getMode()) && Boolean.TRUE.equals(run.getCompleted())
                && Objects.equals(run.getAuthorityFingerprint(), provider.authorityFingerprint())
                && DirectorySyncConfiguration.roots(scope.getRoots()).stream().anyMatch(root ->
                    root.externalDepartmentId().equals(mapping.getRootId())
                    && root.organizationId().equals(mapping.getOrganizationId()));
    }

    JoinApplication application(String tenant, String provider, String enterprise, String externalId) {
        return applications.query(Criteria.of().eq("tenantId", tenant).eq("providerId", provider)
                .eq("externalEnterpriseId", enterprise).eq("externalUserId", externalId), new PageRequest(0, 1))
                .stream().findFirst().orElse(null);
    }

    JoinApplication request(TenantIdentityPolicy policy, IdentityProvider provider, String enterprise, Admission admission) {
        var existing = application(policy.getTenantId(), provider.getId(), enterprise, admission.person().id());
        if (existing != null) return existing;
        var row = new JoinApplication();
        row.setTenantId(policy.getTenantId()); row.setProviderId(provider.getId()); row.setAuthorityFingerprint(providers.descriptorOf(provider).authorityFingerprint());
        row.setExternalEnterpriseId(enterprise); row.setExternalUserId(admission.person().id());
        row.setDisplayName(admission.person().title()); row.setStatus(JoinApplicationStatus.PENDING);
        DirectorySyncStore.insert(applications, row);
        return row;
    }

    ExternalIdentity provision(TenantIdentityPolicy policy, IdentityProviderDescriptor provider, Admission admission) {
        try (var tenant = TenantContext.use(policy.getTenantId())) {
            var binding = writer.provision(policy, provider, admission.person(), admission.memberships(), admission.primarySource());
            binding.setSourceCheckedAt(admission.checkedAt());
            DirectorySyncStore.save((ExternalIdentityDao) identities.getDao(), binding);
            return binding;
        }
    }

    void review(JoinApplication selected, boolean approve, String note, java.util.function.Consumer<JoinApplication> persist) {
        var provider = approve ? providers.findUsable(selected.getProviderId()).orElseThrow(ExternalProvisioningService::rejected) : null;
        var descriptor = approve ? providers.descriptorOf(provider) : null;
        var observation = approve ? observe(descriptor, selected.getExternalUserId()) : null;
        identities.inAdmissionTransaction(() -> {
            identities.lockSource(selected.getTenantId(), selected.getProviderId());
            policies.verifyActiveTenant(selected.getTenantId());
            if (approve && !locks.configuration(selected.getTenantId(), selected.getProviderId())) throw rejected();
            var row = applications.findById(selected.getId());
            if (row == null || !Objects.equals(row.getTenantId(), TenantContext.currentTenantId().orElseThrow())) throw rejected();
            if (row.getStatus() != JoinApplicationStatus.PENDING) return null;
            if (approve) {
                var latest = providers.findUsable(row.getProviderId()).orElseThrow(ExternalProvisioningService::rejected);
                var policy = policies.enabledPolicy(row.getTenantId(), row.getProviderId()).orElseThrow(ExternalProvisioningService::rejected);
                if (!Objects.equals(row.getAuthorityFingerprint(), providers.descriptorOf(latest).authorityFingerprint()) || !descriptor.sameAuthority(providers.descriptorOf(latest))
                        || policy.getProvisioningMode() != ProvisioningMode.REVIEW || !Boolean.TRUE.equals(policy.getLoginEnabled())) throw rejected();
                var admission = admit(policy, descriptor, row.getExternalUserId(), row.getExternalEnterpriseId(), observation);
                var binding = identities.findByExternalIdentity(descriptor.identityNamespace(), row.getExternalEnterpriseId(), row.getExternalUserId())
                        .orElseGet(() -> provision(policy, authorityEnterprise(descriptor, row.getExternalEnterpriseId()), admission));
                row.setUserId(binding.getUserId());
            }
            row.setStatus(approve ? JoinApplicationStatus.APPROVED : JoinApplicationStatus.REJECTED);
            row.setReviewNote(note); row.setReviewedAt(Instant.now());
            row.setReviewerId(CurrentUserContext.currentUser().orElseThrow().userId());
            persist.accept(row);
            return null;
        });
    }

    IdentityProviderDescriptor authorityEnterprise(IdentityProviderDescriptor provider, String enterprise) {
        return new IdentityProviderDescriptor(provider.id(), provider.providerType(), enterprise, provider.identityNamespace(),
                provider.clientId(), provider.clientSecret(), provider.configuration());
    }

    static RuntimeException rejected() {
        return BusinessExceptions.warningText("iam.federation.admission-rejected", TextReference.code(
                "iam.federation.admission-rejected", "无法确认有效的准入资料，请联系管理员检查准入范围并更新通讯录后重试"));
    }
}
