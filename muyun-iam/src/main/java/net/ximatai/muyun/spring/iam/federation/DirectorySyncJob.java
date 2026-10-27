package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.platform.job.*;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.Objects;
import java.util.function.Supplier;

/** Paginated directory reader. Vendor calls precede short fenced page commits. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySyncJob implements JobHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DirectorySyncJob.class);
    public static final String TYPE = "iam.directory-sync";
    public static final String CHANNEL = "directory-sync";
    private static final int PAGE_SIZE = 100;
    private final DirectorySyncStore store;
    private final IdentityProviderService providers;
    private final DirectorySyncConfiguration configuration;
    private final DirectorySyncWriter writer;

    public DirectorySyncJob(
            DirectorySyncStore store,
            IdentityProviderService providers,
            DirectorySyncConfiguration configuration,
            DirectorySyncWriter writer) {
        this.store = Objects.requireNonNull(store, "store");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public JobDefinition definition() {
        return new JobDefinition(
                TYPE,
                "iam.job.type.directorySync",
                JobScope.TENANT,
                null,
                CHANNEL,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                3,
                Duration.ZERO,
                input -> (String) input.get("providerId"),
                true,
                3,
                true,
                null);
    }

    public boolean retryAutomatically(JobRun run) {
        return false;
    }

    public void lockBusiness(Map<String, Object> input) {
        store.lock(input);
    }

    public void planStateChanged(JobPlan plan) {
        store.planStateChanged(plan);
    }

    public void stateChanged(JobRun run) {
        store.stateChanged(run);
    }

    public Map<String, Object> retryParameters(Map<String, Object> original, String originalRunId) {
        var copy = new LinkedHashMap<>(original);
        var rows =
                store.runs.query(
                        Criteria.of()
                                .eq("tenantId", original.get("tenantId"))
                                .eq("jobRunId", originalRunId),
                        new PageRequest(0, 1));
        if (!rows.isEmpty()) {
            if (Boolean.TRUE.equals(rows.getFirst().getRetentionStarted())) {
                throw new JobFailure("DIRECTORY_CONTINUATION_EXPIRED", false);
            }
            copy.put("syncRunId", rows.getFirst().getId());
        }
        return Map.copyOf(copy);
    }

    public static class Cursor {
        public String phase = "DEPARTMENTS";
        public int root;
        public String page;
        public String departmentRow;
        public String department;
        public String personRow;
        public boolean incompleteObservations;
        public String bindingRow;
        public String bindingPositionRow;
        public String processingBindingRow;
    }

    public static class Statistics {
        public long organizations, people, duplicates, conflicts, failedPages, unknownStatus;
        public List<String> failures = new ArrayList<>();
    }

    public JobResult execute(JobContext context) {
        var scope = store.scopes.findById((String) context.parameters().get("scopeId"));
        if (scope == null
                || !Objects.equals(scope.getTenantId(), context.tenantId())
                || !scope.getPolicyId().equals(context.parameters().get("policyId")))
            throw new JobFailure("DIRECTORY_SCOPE_UNAVAILABLE", false);
        var provider =
                providers.descriptorOf(
                        providers
                                .findUsable(scope.getProviderId())
                                .orElseThrow(
                                        () ->
                                                new JobFailure(
                                                        "DIRECTORY_PROVIDER_UNAVAILABLE", false)));
        if (provider.externalEnterpriseId() == null)
            throw new JobFailure("DIRECTORY_ENTERPRISE_REQUIRED", false);
        var protocol = configuration.protocol(provider);
        var roots = DirectorySyncConfiguration.roots(scope.getRoots());
        DirectorySyncRun initial =
                context.transaction(
                        () -> lockBusiness(context.parameters()),
                        () -> {
                            var rows =
                                    store.runs.query(
                                            Criteria.of()
                                                    .eq("tenantId", context.tenantId())
                                                    .eq("jobRunId", context.runId()),
                                            new PageRequest(0, 1));
                            if (!rows.isEmpty()) return rows.getFirst();
                            if (context.parameters().get("syncRunId")
                                    instanceof String continuation) {
                                var retained = store.runs.findById(continuation);
                                if (retained == null
                                        || Boolean.TRUE.equals(retained.getRetentionStarted())
                                        || !Objects.equals(
                                                retained.getTenantId(), context.tenantId())
                                        || !scope.getId().equals(retained.getScopeId()))
                                    throw new JobFailure("DIRECTORY_CONTINUATION_INVALID", false);
                                retained.setJobRunId(context.runId());
                                retained.setStatus("RUNNING");
                                DirectorySyncStore.save(store.runs, retained);
                                return retained;
                            }
                            var row = new DirectorySyncRun();
                            row.setAuthorityFingerprint(provider.authorityFingerprint());
                            row.setJobRunId(context.runId());
                            row.setTenantId(context.tenantId());
                            row.setPolicyId(scope.getPolicyId());
                            row.setScopeId(scope.getId());
                            row.setMode((String) context.parameters().get("mode"));
                            row.setStatus("RUNNING");
                            row.setCursor(DirectorySyncStore.encode(new Cursor()));
                            row.setStatistics(DirectorySyncStore.encode(new Statistics()));
                            row.setCompleted(false);
                            DirectorySyncStore.insert(store.runs, row);
                            return row;
                        });
        boolean peopleProgressReported = false;
        while (true) {
            var run = reload(initial);
            if (!Objects.equals(run.getAuthorityFingerprint(), provider.authorityFingerprint()))
                throw new JobFailure("DIRECTORY_AUTHORITY_CHANGED", false);
            context.checkCancelled();
            var cursor = DirectorySyncStore.decode(run.getCursor(), Cursor.class);
            var stats = DirectorySyncStore.decode(run.getStatistics(), Statistics.class);
            if ("FINISHED".equals(cursor.phase)) {
                context.progress(100);
                return new JobResult(
                        stats.failedPages > 0 || stats.conflicts > 0
                                ? "DIRECTORY_PARTIAL"
                                : stats.unknownStatus > 0 ? "DIRECTORY_UNKNOWN_STATUS" : "DIRECTORY_COMPLETED",
                        Map.of(
                                "organizations",
                                stats.organizations,
                                "people",
                                stats.people,
                                "duplicates",
                                stats.duplicates,
                                "unknownStatus",
                                stats.unknownStatus,
                                "conflicts",
                                stats.conflicts,
                                "failedPages",
                                stats.failedPages),
                        stats.failedPages > 0 || stats.conflicts > 0 || stats.unknownStatus > 0);
            }
            if ("WRITING_PEOPLE".equals(cursor.phase)) {
                var query = Criteria.of().eq("tenantId", run.getTenantId()).eq("runId", run.getId());
                if (cursor.personRow != null) query.gt("id", cursor.personRow);
                var batch = store.seen.query(query, new PageRequest(0, 1), Sort.asc("id"));
                if (batch.isEmpty()) {
                    run.setCompleted(stats.failedPages == 0 && stats.conflicts == 0);
                    cursor.phase = Boolean.TRUE.equals(run.getCompleted()) && "SYNC".equals(run.getMode())
                            ? "FINALIZING" : "FINISHED";
                    commit(context, run, cursor, stats, () -> configuration.completeScopePreview(run));
                } else {
                    // One person is the atomic IAM aggregate; the durable keyset cursor fences replay.
                    var seen = batch.getFirst();
                    try {
                        commit(context, run, cursor, stats, () -> {
                            providers.requireDirectoryAuthority(context.tenantId(), provider);
                            var person = DirectorySyncStore.decode(seen.getPerson(), ExternalDirectoryProtocol.Person.class)
                                    .withMemberships(seen.getPrimaryDepartmentId(),
                                            List.of(DirectorySyncStore.decode(seen.getDepartmentIds(), String[].class)));
                            var resolved = writer.resolvePrimary(run, provider, person);
                            seen.setPrimaryDepartmentId(resolved.primaryDepartmentId());
                            seen.setPrimaryDepartmentSource(person.primaryDepartmentId() != null
                                    && person.primaryDepartmentId().equals(resolved.primaryDepartmentId()) ? "vendor" : "inferred");
                            DirectorySyncStore.save(store.seen, seen);
                            // Partial membership collection cannot authorize active placement changes.
                            // Confirmed inactive observations still revoke the source and its sessions.
                            if ((!cursor.incompleteObservations || !"active".equals(person.status()))
                                    && !writer.person(run, scope, provider, resolved, seen.getPrimaryDepartmentSource()))
                                stats.conflicts++;
                            cursor.personRow = seen.getId();
                        });
                    } catch (JobFailure failure) {
                        throw failure;
                    } catch (RuntimeException failure) {
                        var recovered = reload(run);
                        var recoveredCursor = DirectorySyncStore.decode(recovered.getCursor(), Cursor.class);
                        var recoveredStats = DirectorySyncStore.decode(recovered.getStatistics(), Statistics.class);
                        failure(recoveredStats, "person-write", seen.getExternalUserId(), seen.getId());
                        recoveredCursor.personRow = seen.getId();
                        commit(context, recovered, recoveredCursor, recoveredStats, () -> {});
                    }
                }
                continue;
            }
            if ("FINALIZING".equals(cursor.phase)) {
                commit(context, run, cursor, stats, () -> {
                    if (!configuration.canFinalize(scope)) {
                        cursor.phase = "FINISHED";
                        return;
                    }
                    providers.requireDirectoryAuthority(context.tenantId(), provider);
                    writer.finalizeMissing(run, scope, cursor);
                });
                continue;
            }
            if ("PEOPLE".equals(cursor.phase) && !peopleProgressReported) {
                context.progress(50);
                peopleProgressReported = true;
                // Progress mirrors job state into the topic row; reload its optimistic version.
                continue;
            }
            if ("DEPARTMENTS".equals(cursor.phase)) {
                if (cursor.root >= roots.size()) {
                    cursor.phase = "PEOPLE";
                    cursor.page = null;
                    commit(context, run, cursor, stats, () -> {});
                    continue;
                }
                var root = roots.get(cursor.root);
                ExternalDirectoryProtocol.Page<ExternalDirectoryProtocol.Department> page;
                try {
                    page =
                            read(
                                    () ->
                                            protocol.departments(
                                                    provider,
                                                    root.externalDepartmentId(),
                                                    cursor.page,
                                                    PAGE_SIZE));
                    for (var department : page.items()) {
                        var ancestors = read(() -> DirectorySyncConfiguration.ancestors(protocol, provider, department.id()));
                        if (!department.id().equals(root.externalDepartmentId())
                                && !ancestors.contains(root.externalDepartmentId()))
                            throw new IllegalArgumentException("OUTSIDE_DIRECTORY_SCOPE");
                        var detail = read(() -> protocol.department(provider, department.id()));
                        if (!Objects.equals(detail.parentId(), department.parentId()))
                            throw new IllegalArgumentException("DIRECTORY_DEPARTMENT_MISMATCH");
                    }
                } catch (JobFailure failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    failure(stats, failure instanceof ExternalDirectoryProtocol.ReadLimitExceededException
                            ? "departments-limit-select-narrower-roots" : "departments",
                            root.externalDepartmentId(), cursor.page);
                    cursor.root++;
                    cursor.page = null;
                    commit(context, run, cursor, stats, () -> {});
                    continue;
                }
                String next = page.nextCursor();
                if (next != null && next.equals(cursor.page))
                    throw new JobFailure("DIRECTORY_CURSOR_NOT_ADVANCING", false);
                Cursor after =
                        DirectorySyncStore.decode(DirectorySyncStore.encode(cursor), Cursor.class);
                after.page = next;
                if (next == null) after.root++;
                try {
                    commit(
                            context,
                            run,
                            after,
                            stats,
                            () -> {
                                providers.requireDirectoryAuthority(context.tenantId(), provider);
                                for (var department : page.items()) {
                                    if (!store.departments
                                            .query(
                                                    Criteria.of()
                                                            .eq("tenantId", run.getTenantId())
                                                            .eq("runId", run.getId())
                                                            .eq(
                                                                    "externalDepartmentId",
                                                                    department.id()),
                                                    new PageRequest(0, 1))
                                            .isEmpty()) continue;
                                    var seen = new DirectorySyncDepartment();
                                    seen.setRunId(run.getId());
                                    seen.setTenantId(run.getTenantId());
                                    seen.setExternalDepartmentId(department.id());
                                    seen.setRootId(root.externalDepartmentId());
                                    seen.setOrganizationId(root.organizationId());
                                    DirectorySyncStore.insert(store.departments, seen);
                                    if (!"SYNC".equals(run.getMode())) {
                                        if (writer.departmentConflict(run, scope, root, department))
                                            stats.conflicts++;
                                    } else writer.department(run, scope, root, department);
                                    stats.organizations++;
                                }
                            });
                } catch (JobFailure failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    // Exception messages and causes may contain vendor data; log code locations only.
                    LOG.warn("Directory department page commit failed: type={}, frames={}",
                            failure.getClass().getName(), Arrays.stream(failure.getStackTrace()).limit(8).toList());
                    var recovered = reload(run);
                    var recoveredCursor =
                            DirectorySyncStore.decode(recovered.getCursor(), Cursor.class);
                    var recoveredStats =
                            DirectorySyncStore.decode(recovered.getStatistics(), Statistics.class);
                    failure(
                            recoveredStats,
                            "department-write",
                            root.externalDepartmentId(),
                            recoveredCursor.page);
                    recoveredCursor.root++;
                    recoveredCursor.page = null;
                    commit(context, recovered, recoveredCursor, recoveredStats, () -> {});
                }
            } else {
                if (cursor.department == null) {
                    Criteria query =
                            Criteria.of()
                                    .eq("tenantId", run.getTenantId())
                                    .eq("runId", run.getId());
                    if (cursor.departmentRow != null) query.gt("id", cursor.departmentRow);
                    var next =
                            store.departments.query(query, new PageRequest(0, 1), Sort.asc("id"));
                    if (next.isEmpty()) {
                        cursor.phase = "WRITING_PEOPLE";
                        cursor.incompleteObservations = stats.failedPages > 0;
                        commit(context, run, cursor, stats, () -> {});
                        continue;
                    }
                    cursor.departmentRow = next.getFirst().getId();
                    cursor.department = next.getFirst().getExternalDepartmentId();
                    cursor.page = null;
                    commit(context, run, cursor, stats, () -> {});
                    continue;
                }
                ExternalDirectoryProtocol.Page<ExternalDirectoryProtocol.Person> page;
                try {
                    page =
                            read(
                                    () ->
                                            protocol.departmentPeople(
                                                    provider,
                                                    cursor.department,
                                                    cursor.page,
                                                    PAGE_SIZE));
                } catch (JobFailure failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    failure(stats, failure instanceof ExternalDirectoryProtocol.ReadLimitExceededException
                            ? "people-limit-select-narrower-roots" : "people", cursor.department, cursor.page);
                    cursor.department = null;
                    cursor.page = null;
                    commit(context, run, cursor, stats, () -> {});
                    continue;
                }
                if (page.nextCursor() != null && page.nextCursor().equals(cursor.page))
                    throw new JobFailure("DIRECTORY_CURSOR_NOT_ADVANCING", false);
                Cursor after =
                        DirectorySyncStore.decode(DirectorySyncStore.encode(cursor), Cursor.class);
                after.page = page.nextCursor();
                if (after.page == null) after.department = null;
                var currentRun = run;
                var currentStats = stats;
                try {
                    commit(
                            context,
                            run,
                            after,
                            stats,
                            () -> {
                                providers.requireDirectoryAuthority(context.tenantId(), provider);
                                for (var person : page.items()) {
                                    if (person.id() == null || person.id().isBlank() || person.title() == null
                                            || !Set.of("active", "disabled", "departed", "unknown").contains(person.status()))
                                        throw new IllegalArgumentException("DIRECTORY_INVALID_PERSON");
                                    var rows = store.seen.query(Criteria.of()
                                            .eq("tenantId", currentRun.getTenantId()).eq("runId", currentRun.getId())
                                            .eq("externalUserId", person.id()), new PageRequest(0, 1));
                                    boolean fresh = rows.isEmpty();
                                    var seen = fresh ? new DirectorySyncSeen() : rows.getFirst();
                                    var memberships = new LinkedHashSet<String>();
                                    if (!fresh) {
                                        memberships.addAll(List.of(DirectorySyncStore.decode(seen.getDepartmentIds(), String[].class)));
                                        if (!Objects.equals(seen.getSourceStatus(), person.status())
                                                || seen.getPrimaryDepartmentId() != null && person.primaryDepartmentId() != null
                                                && !seen.getPrimaryDepartmentId().equals(person.primaryDepartmentId()))
                                            throw new IllegalArgumentException("DIRECTORY_INCONSISTENT_PERSON");
                                        currentStats.duplicates++;
                                    } else {
                                        seen.setTenantId(currentRun.getTenantId());
                                        seen.setRunId(currentRun.getId());
                                        seen.setExternalUserId(person.id());
                                        seen.setPerson(DirectorySyncStore.encode(person.withMemberships(null, List.of())));
                                        seen.setSourceStatus(person.status());
                                        currentStats.people++;
                                        if ("unknown".equals(person.status())) currentStats.unknownStatus++;
                                    }
                                    // The page department is authoritative even when the vendor omits all memberships.
                                    memberships.add(cursor.department);
                                    memberships.addAll(person.departmentIds());
                                    seen.setDepartmentIds(DirectorySyncStore.encode(memberships));
                                    if (person.primaryDepartmentId() != null) seen.setPrimaryDepartmentId(person.primaryDepartmentId());
                                    if (fresh) DirectorySyncStore.insert(store.seen, seen);
                                    else DirectorySyncStore.save(store.seen, seen);
                                }
                            });
                } catch (JobFailure failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    var recovered = reload(run);
                    var recoveredCursor =
                            DirectorySyncStore.decode(recovered.getCursor(), Cursor.class);
                    var recoveredStats =
                            DirectorySyncStore.decode(recovered.getStatistics(), Statistics.class);
                    failure(
                            recoveredStats,
                            "people-write",
                            recoveredCursor.department,
                            recoveredCursor.page);
                    recoveredCursor.department = null;
                    recoveredCursor.page = null;
                    commit(context, recovered, recoveredCursor, recoveredStats, () -> {});
                }
            }
        }
    }

    private static <T> T read(Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return request.get();
            } catch (IllegalArgumentException | JobFailure permanent) {
                throw permanent;
            } catch (RuntimeException unavailable) {
                if (attempt == 2) throw unavailable;
                try {
                    Thread.sleep(50L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new JobFailure("DIRECTORY_READ_INTERRUPTED", false);
                }
            }
        }
    }

    private DirectorySyncRun reload(DirectorySyncRun run) {
        return store.runs.findById(run.getId());
    }

    private static void failure(Statistics stats, String phase, String id, String cursor) {
        stats.failedPages++;
        // Store only page references, never provider messages, credentials or response bodies.
        if (stats.failures.size() < 100)
            stats.failures.add(phase + ":" + id + ":" + (cursor == null ? "0" : cursor));
    }

    private void commit(
            JobContext context,
            DirectorySyncRun run,
            Cursor cursor,
            Statistics stats,
            Runnable writes) {
        context.transaction(
                () -> lockBusiness(context.parameters()),
                () -> {
                    if ("SYNC".equals(run.getMode())) {
                        configuration.requireActiveScope(store.scopes.findById(run.getScopeId()));
                    }
                    writes.run();
                    run.setCursor(DirectorySyncStore.encode(cursor));
                    run.setStatistics(DirectorySyncStore.encode(stats));
                    run.setFailedPages(DirectorySyncStore.encode(stats.failures));
                    DirectorySyncStore.save(store.runs, run);
                    return null;
                });
    }
}
