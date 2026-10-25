package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.iam.department.*;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.user.*;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.Objects;

/** Directory ownership decisions and IAM mutations; no vendor calls or execution state machine. */
@Component
@ConditionalOnExternalIdentityFederation
final class DirectorySyncWriter {
    private final DirectorySyncStore store;
    private final DepartmentService departments;
    private final EmployeeService employees;
    private final EmployeePositionService positions;
    private final UserAccountService accounts;
    private final EmployeeAccountService employeeAccounts;
    private final ExternalIdentityService identities;
    private final RoleService roles;
    private final DirectorySyncConfiguration configuration;
    private final DirectorySourceLifecycle lifecycle;

    DirectorySyncWriter(
            DirectorySyncStore store,
            DepartmentService departments,
            EmployeeService employees,
            EmployeePositionService positions,
            UserAccountService accounts,
            EmployeeAccountService employeeAccounts,
            ExternalIdentityService identities,
            RoleService roles,
            DirectorySyncConfiguration configuration,
            DirectorySourceLifecycle lifecycle) {
        this.store = Objects.requireNonNull(store, "store");
        this.departments = Objects.requireNonNull(departments, "departments");
        this.employees = Objects.requireNonNull(employees, "employees");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.employeeAccounts = Objects.requireNonNull(employeeAccounts, "employeeAccounts");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    boolean departmentConflict(
            DirectorySyncRun run,
            DirectorySyncScope scope,
            DirectorySyncConfiguration.Root root,
            ExternalDirectoryProtocol.Department external) {
        var mapping = store.mapping(run.getTenantId(), scope.getProviderId(), external.id());
        return mapping != null && departments.selectActiveRaw(mapping.getDepartmentId()) == null;
    }

    void department(
            DirectorySyncRun run,
            DirectorySyncScope scope,
            DirectorySyncConfiguration.Root root,
            ExternalDirectoryProtocol.Department external) {
        var map = store.mapping(run.getTenantId(), scope.getProviderId(), external.id());
        boolean fresh = map == null;
        if (fresh) {
            map = new DirectoryDepartmentMapping();
            map.setTenantId(run.getTenantId());
            map.setProviderId(scope.getProviderId());
            map.setExternalDepartmentId(external.id());
        }
        // A cross-organization mapping is only reachable through a confirmed scope revision.
        // Keep the old department as history and point the source mapping at a new local department.
        boolean replacement = !fresh && !root.organizationId().equals(map.getOrganizationId());
        var department =
                fresh || replacement ? new Department() : departments.selectActiveRaw(map.getDepartmentId());
        if (department == null) throw new IllegalArgumentException("MAPPED_DEPARTMENT_UNAVAILABLE");
        department.setOrganizationId(root.organizationId());
        department.setTitle(external.title());
        if (fresh || replacement) department.setCode("ext_" + digest(scope.getProviderId() + ":" + external.id()
                + (replacement ? ":" + scope.getId() : "")));
        String parent = TreeAbility.ROOT_ID;
        if (!external.id().equals(root.externalDepartmentId())) {
            var parentMap =
                    store.mapping(run.getTenantId(), scope.getProviderId(), external.parentId());
            if (parentMap == null) throw new IllegalArgumentException("PARENT_MAPPING_MISSING");
            parent = parentMap.getDepartmentId();
        }
        department.setParentId(parent);
        if (fresh || replacement) departments.insert(department);
        else departments.update(department);
        map.setRootId(root.externalDepartmentId());
        map.setOrganizationId(root.organizationId());
        map.setDepartmentId(department.getId());
        map.setParentExternalId(external.parentId());
        map.setLastRunId(run.getId());
        map.setLastSeenAt(Instant.now());
        if (fresh) DirectorySyncStore.insert(store.mappings, map);
        else DirectorySyncStore.save(store.mappings, map);
    }

    /** Returns false on an identity conflict; preview performs exactly these ownership checks without mutations. */
    boolean person(
            DirectorySyncRun run,
            DirectorySyncScope scope,
            IdentityProviderDescriptor provider,
            ExternalDirectoryProtocol.Person person) {
        var binding =
                identities
                        .findByExternalIdentity(
                                provider.identityNamespace(),
                                provider.externalEnterpriseId(),
                                person.id())
                        .orElse(null);
        if (binding == null && identities.isAutoRebindBlocked(provider.id(), person.id())) return false;
        if (binding != null
                && (binding.getBindingSource() != ExternalIdentityBindingSource.DIRECTORY_SYNC
                        || !scope.getProviderId().equals(binding.getProviderId()))) return false;
        if (binding != null && !"active".equals(person.status())) {
            if (!"SYNC".equals(run.getMode())) return true;
            binding.setSourceStatus(person.status());
            binding.setSourceCheckedAt(Instant.now());
            binding.setLastSyncRunId(run.getId());
            identities.update(binding);
            lifecycle.invalidate(binding, person.status(), run.getId());
            return true;
        }
        var memberships = new ArrayList<DirectoryDepartmentMapping>();
        for (String id : new LinkedHashSet<>(person.departmentIds())) {
            var observed =
                    store.departments.query(
                            Criteria.of()
                                    .eq("tenantId", run.getTenantId())
                                    .eq("runId", run.getId())
                                    .eq("externalDepartmentId", id),
                            new PageRequest(0, 1));
            if (observed.isEmpty()) continue;
            var map = store.mapping(run.getTenantId(), scope.getProviderId(), id);
            if (map != null) memberships.add(map);
        }
        String username = username(scope, person);
        if (!"SYNC".equals(run.getMode())) {
            if (binding != null) {
                var accountLink = employeeAccounts.accountOfUser(binding.getUserId());
                if (accountLink == null || accounts.selectActiveRaw(binding.getUserId()) == null)
                    return false;
                var retainedEmployee = employees.selectActiveRaw(accountLink.getEmployeeId());
                if (retainedEmployee == null || manualPrimaryConflict(retainedEmployee.getId())) return false;
                var main =
                        memberships.stream()
                                .filter(
                                        m ->
                                                m.getExternalDepartmentId()
                                                        .equals(person.primaryDepartmentId()))
                                .findFirst()
                                .orElse(memberships.isEmpty() ? null : memberships.getFirst());
                // A primary department move is an ordinary source update, not an identity conflict.
                return main != null || "SCOPE_PREVIEW".equals(run.getMode());
            }
            return usernameAvailable(username);
        }
        if (memberships.isEmpty()) return false;
        var primary =
                memberships.stream()
                        .filter(
                                m ->
                                        m.getExternalDepartmentId()
                                                .equals(person.primaryDepartmentId()))
                        .findFirst()
                        .orElse(memberships.getFirst());
        Employee employee;
        if (binding == null) {
            if (!usernameAvailable(username)
                    || !employees
                            .listRaw(
                                    Criteria.of()
                                            .eq("organizationId", primary.getOrganizationId())
                                            .eq("employeeNo", employeeNo(scope, person)),
                                    new PageRequest(0, 1))
                            .isEmpty()) return false;
            employee = new Employee();
            employee.setTitle(person.title());
            employee.setEmployeeNo(employeeNo(scope, person));
            employee.setOrganizationId(primary.getOrganizationId());
            employee.setDepartmentId(primary.getDepartmentId());
            employee.setMobile(person.mobile());
            employee.setEmail(person.email());
            employees.insert(employee);
            var account = new UserAccount();
            account.setUsername(username);
            account.setTitle(person.title());
            account.setPasswordStatus(PasswordStatus.NONE);
            accounts.insert(account);
            var link = new EmployeeAccount();
            link.setEmployeeId(employee.getId());
            link.setUserId(account.getId());
            employeeAccounts.insert(link);
            binding = new ExternalIdentity();
            binding.setUserId(account.getId());
            binding.setProviderId(provider.id());
            binding.setIdentityNamespace(provider.identityNamespace());
            binding.setExternalEnterpriseId(provider.externalEnterpriseId());
            binding.setExternalUserId(person.id());
            binding.setBoundAt(Instant.now());
            binding.setBindingSource(ExternalIdentityBindingSource.DIRECTORY_SYNC);
            binding.setDisplayName(person.title());
            binding.setSourceStatus(person.status());
            binding.setSourceCheckedAt(Instant.now());
            binding.setLastSyncRunId(run.getId());
            identities.insert(binding);
        } else {
            var accountLink = employeeAccounts.accountOfUser(binding.getUserId());
            if (accountLink == null || accounts.selectActiveRaw(binding.getUserId()) == null)
                return false;
            // Match aggregate writes: lock the employee row before its child partition.
            store.database.query("select id from iam_employee where tenant_id=? and id=? for update",
                    run.getTenantId(), accountLink.getEmployeeId());
            positions.lockParentMutation(accountLink.getEmployeeId());
            employee = employees.selectActiveRaw(accountLink.getEmployeeId());
            if (employee == null || manualPrimaryConflict(employee.getId())) return false;
            // Retire old primaries before moving the employee and creating the new primary.
            employee.setOrganizationId(primary.getOrganizationId());
            employee.setDepartmentId(primary.getDepartmentId());
            employee.setTitle(person.title());
            employee.setEmployeeNo(employeeNo(scope, person));
            employee.setMobile(person.mobile());
            employee.setEmail(person.email());
            employees.update(employee);
            var account = accounts.selectActiveRaw(binding.getUserId());
            account.setTitle(person.title());
            accounts.update(account);
            binding.setDisplayName(person.title());
            binding.setSourceStatus(person.status());
            binding.setSourceCheckedAt(Instant.now());
            binding.setLastSyncRunId(run.getId());
            identities.update(binding);
        }
        if (!"active".equals(person.status())) {
            lifecycle.invalidate(binding, person.status(), run.getId());
            return true;
        }
        // Only source-owned positions participate. Local enablement is never restored.
        var retainedPositions = positions.listRaw(Criteria.of().eq("employeeId", employee.getId())
                .eq("source", "directory").eq("sourceProviderId", provider.id()),
                new PageRequest(0, Integer.MAX_VALUE));
        for (var old : retainedPositions) {
            boolean present = memberships.stream().anyMatch(m ->
                    m.getDepartmentId().equals(old.getDepartmentId())
                            && scope.getPositionId().equals(old.getPositionId()));
            boolean primaryNow = present && primary.getDepartmentId().equals(old.getDepartmentId());
            if (!present || Boolean.TRUE.equals(old.getPrimaryPosition()) && !primaryNow) {
                lifecycle.retire(old, present ? "active" : "retired", run.getId());
            }
        }
        for (var membership : memberships) {
            var position =
                    positions
                            .listRaw(
                                    Criteria.of()
                                            .eq("employeeId", employee.getId())
                                            .eq("source", "directory")
                                            .eq("sourceProviderId", provider.id())
                                            .eq("departmentId", membership.getDepartmentId())
                                            .eq("positionId", scope.getPositionId()),
                                    new PageRequest(0, 1))
                            .stream()
                            .findFirst()
                            .orElse(null);
            boolean fresh = position == null;
            if (fresh) {
                position = new EmployeePosition();
                position.setEmployeeId(employee.getId());
                position.setSource("directory");
                position.setSourceProviderId(provider.id());
                position.setExternalDepartmentId(membership.getExternalDepartmentId());
                position.setOrganizationId(membership.getOrganizationId());
                position.setDepartmentId(membership.getDepartmentId());
                position.setPositionId(scope.getPositionId());
                position.setPrimaryPosition(membership == primary);
            }
            position.setSourceStatus(person.status());
            position.setLastSyncRunId(run.getId());
            if (fresh) {
                // Enabled reference validation must not restore a disabled employee or create
                // grants beneath it.
                if (!Boolean.TRUE.equals(employee.getEnabled())) continue;
                positions.insert(position);
            } else {
                var retained = position;
                VersionedRecordMutation.update(
                        positions.getDao(),
                        () -> positions.selectActiveRaw(retained.getId()),
                        row -> {
                            row.setSourceStatus(person.status());
                            row.setPrimaryPosition(membership == primary);
                            row.setLastSyncRunId(run.getId());
                        });
            }
            roles.retainDirectoryEmploymentRole(position.getId(), provider.id(),
                    membership == primary ? scope.getRoleId() : null);
            // The retained object may have changed primary status in the internal mutation above.
            position.setPrimaryPosition(membership == primary);
            if (scope.getRoleId() != null
                    && Boolean.TRUE.equals(position.getPrimaryPosition())
                    && Boolean.TRUE.equals(position.getEnabled())
                    && Boolean.TRUE.equals(employee.getEnabled())) {
                configuration.validateRole(scope.getRoleId());
                roles.ensureDirectoryEmploymentRole(scope.getRoleId(), position.getId(), provider.id());
            }
        }
        return true;
    }

    /** One stable keyset page per fenced transaction. The cursor commits with source retirement. */
    void finalizeMissing(DirectorySyncRun run, DirectorySyncScope scope, DirectorySyncJob.Cursor cursor) {
        var query = Criteria.of().eq("providerId", scope.getProviderId())
                .eq("bindingSource", ExternalIdentityBindingSource.DIRECTORY_SYNC);
        if (cursor.bindingRow != null) query.gt("id", cursor.bindingRow);
        var batch = identities.listRaw(query, new PageRequest(0, 1), net.ximatai.muyun.database.core.orm.Sort.asc("id"));
        if (batch.isEmpty()) {
            cursor.phase = "FINISHED";
            return;
        }
        var binding = batch.getFirst();
        if (store.seen.query(Criteria.of().eq("tenantId", run.getTenantId())
                .eq("runId", run.getId()).eq("externalUserId", binding.getExternalUserId()),
                new PageRequest(0, 1)).isEmpty()) {
            if (!"missing".equals(binding.getSourceStatus())) {
                binding.setSourceStatus("missing");
                binding.setSourceCheckedAt(Instant.now());
                identities.update(binding);
            }
            lifecycle.revoke(binding, "missing");
            cursor.bindingPositionRow = lifecycle.retireBatch(binding, "missing", run.getId(), cursor.bindingPositionRow);
            if (cursor.bindingPositionRow != null) return;
        }
        cursor.bindingRow = binding.getId();
        cursor.bindingPositionRow = null;
    }

    private boolean manualPrimaryConflict(String employeeId) {
        return !positions.listRaw(Criteria.of().eq("employeeId", employeeId).eq("source", "manual")
                .eq("primaryPosition", true).eq("enabled", true), new PageRequest(0, 1)).isEmpty();
    }

    private String username(DirectorySyncScope scope, ExternalDirectoryProtocol.Person person) {
        String number = person.employeeNo();
        // Employee numbers are only unique within an organization. Check the entire tenant before
        // selecting a username.
        if (number != null
                && !number.isBlank()
                && number.length() <= 64
                && employees
                        .listRaw(Criteria.of().eq("employeeNo", number), new PageRequest(0, 2))
                        .isEmpty()
                && usernameAvailable(number)) return number;
        return "ext_" + digest(scope.getProviderId() + ":" + person.id());
    }

    private String employeeNo(DirectorySyncScope scope, ExternalDirectoryProtocol.Person person) {
        return person.employeeNo() == null || person.employeeNo().isBlank()
                ? "ext_" + digest(scope.getProviderId() + ":" + person.id())
                : person.employeeNo();
    }

    private boolean usernameAvailable(String name) {
        return accounts.listRaw(Criteria.of().eq("username", name), new PageRequest(0, 1))
                .isEmpty();
    }

    static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
