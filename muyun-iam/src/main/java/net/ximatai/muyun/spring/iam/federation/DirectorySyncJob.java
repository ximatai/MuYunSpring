package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Paginated directory reader. Vendor calls precede short fenced page commits. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySyncJob implements JobHandler {
    public static final String TYPE = "iam.directory-sync";
    public static final String CHANNEL = "directory-sync";
    private static final int PAGE_SIZE = 100;
    private final DirectorySyncStore store;
    private final IdentityProviderService providers;
    private final DirectorySyncConfiguration configuration;
    private final DirectorySyncWriter writer;
    public DirectorySyncJob(DirectorySyncStore store, IdentityProviderService providers, DirectorySyncConfiguration configuration, DirectorySyncWriter writer) {
        this.store = java.util.Objects.requireNonNull(store, "store"); this.providers = java.util.Objects.requireNonNull(providers, "providers"); this.configuration = java.util.Objects.requireNonNull(configuration, "configuration"); this.writer = java.util.Objects.requireNonNull(writer, "writer");
    }
    public JobDefinition definition() {
        return new JobDefinition(TYPE, "iam.job.type.directorySync", JobScope.TENANT, null, CHANNEL,
                Duration.ofMinutes(5), Duration.ofHours(1), 3, Duration.ZERO,
                input -> (String) input.get("providerId"), true, 3, true, null);
    }
    public boolean retryAutomatically(JobRun run) { return false; }
    public void lockBusiness(Map<String, Object> input) { store.lock(input); }
    public void planStateChanged(JobPlan plan) { store.planStateChanged(plan); }
    public void stateChanged(JobRun run) { store.stateChanged(run); }
    public Map<String, Object> retryParameters(Map<String, Object> original, String originalRunId) {
        var copy = new LinkedHashMap<>(original);
        var rows = store.runs.query(Criteria.of().eq("tenantId", original.get("tenantId")).eq("jobRunId", originalRunId), new PageRequest(0, 1));
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
    }
    public static class Statistics {
        public long organizations, people, duplicates, conflicts, failedPages;
        public List<String> failures = new ArrayList<>();
    }
    public JobResult execute(JobContext context) {
        var scope = store.scopes.findById((String) context.parameters().get("scopeId"));
        if (scope == null || !Objects.equals(scope.getTenantId(), context.tenantId()) || !scope.getPolicyId().equals(context.parameters().get("policyId")))
            throw new JobFailure("DIRECTORY_SCOPE_UNAVAILABLE", false);
        var provider = providers.descriptorOf(providers.findUsable(scope.getProviderId()).orElseThrow(() -> new JobFailure("DIRECTORY_PROVIDER_UNAVAILABLE", false)));
        if (provider.externalEnterpriseId() == null) throw new JobFailure("DIRECTORY_ENTERPRISE_REQUIRED", false);
        var protocol = configuration.protocol(provider);
        var roots = DirectorySyncConfiguration.roots(scope.getRoots());
        DirectorySyncRun initial = context.transaction(() -> lockBusiness(context.parameters()), () -> {
            var rows = store.runs.query(Criteria.of().eq("tenantId", context.tenantId()).eq("jobRunId", context.runId()), new PageRequest(0, 1));
            if (!rows.isEmpty()) return rows.getFirst();
            if (context.parameters().get("syncRunId") instanceof String continuation) {
                var retained = store.runs.findById(continuation);
                if (retained == null || Boolean.TRUE.equals(retained.getRetentionStarted()) || !Objects.equals(retained.getTenantId(), context.tenantId()) || !scope.getId().equals(retained.getScopeId())) throw new JobFailure("DIRECTORY_CONTINUATION_INVALID", false);
                retained.setJobRunId(context.runId()); retained.setStatus("RUNNING"); DirectorySyncStore.save(store.runs, retained); return retained;
            }
            var row = new DirectorySyncRun(); row.setJobRunId(context.runId()); row.setTenantId(context.tenantId()); row.setPolicyId(scope.getPolicyId());
            row.setScopeId(scope.getId()); row.setMode((String) context.parameters().get("mode")); row.setStatus("RUNNING");
            row.setCursor(DirectorySyncStore.encode(new Cursor())); row.setStatistics(DirectorySyncStore.encode(new Statistics())); row.setCompleted(false);
            DirectorySyncStore.insert(store.runs, row); return row;
        });
        while (true) {
            var run = reload(initial);
            context.checkCancelled();
            var cursor = DirectorySyncStore.decode(run.getCursor(), Cursor.class);
            var stats = DirectorySyncStore.decode(run.getStatistics(), Statistics.class);
            if ("FINISHED".equals(cursor.phase)) {
                context.progress(100);
                return new JobResult(stats.failedPages > 0 || stats.conflicts > 0 ? "DIRECTORY_PARTIAL" : "DIRECTORY_COMPLETED",
                    Map.of("organizations", stats.organizations, "people", stats.people, "duplicates", stats.duplicates, "conflicts", stats.conflicts, "failedPages", stats.failedPages), stats.failedPages > 0 || stats.conflicts > 0);
            }
            if ("DEPARTMENTS".equals(cursor.phase)) {
                if (cursor.root >= roots.size()) {
                    cursor.phase = "PEOPLE"; cursor.page = null; commit(context, run, cursor, stats, () -> { }); continue;
                }
                var root = roots.get(cursor.root);
                ExternalDirectoryProtocol.Page<ExternalDirectoryProtocol.Department> page;
                try { page = read(() -> protocol.departments(provider, root.externalDepartmentId(), cursor.page, PAGE_SIZE)); }
                catch (JobFailure failure) { throw failure; }
                catch (RuntimeException failure) { failure(stats, "departments", root.externalDepartmentId(), cursor.page); cursor.root++; cursor.page = null; commit(context, run, cursor, stats, () -> { }); continue; }
                String next = page.nextCursor();
                if (next != null && next.equals(cursor.page)) throw new JobFailure("DIRECTORY_CURSOR_NOT_ADVANCING", false);
                Cursor after = DirectorySyncStore.decode(DirectorySyncStore.encode(cursor), Cursor.class); after.page = next;
                if (next == null) after.root++;
                try {
                    commit(context, run, after, stats, () -> {
                        providers.requireDirectoryAuthority(context.tenantId(), provider);
                        for (var department : page.items()) {
                            if (!department.id().equals(root.externalDepartmentId()) && !department.ancestorIds().contains(root.externalDepartmentId())) throw new IllegalArgumentException("OUTSIDE_DIRECTORY_SCOPE");
                            if (!store.departments.query(Criteria.of().eq("tenantId", run.getTenantId()).eq("runId", run.getId()).eq("externalDepartmentId", department.id()), new PageRequest(0, 1)).isEmpty()) continue;
                            var seen = new DirectorySyncDepartment(); seen.setRunId(run.getId()); seen.setTenantId(run.getTenantId()); seen.setExternalDepartmentId(department.id()); seen.setRootId(root.externalDepartmentId()); seen.setOrganizationId(root.organizationId());
                            DirectorySyncStore.insert(store.departments, seen);
                            if ("PREVIEW".equals(run.getMode())) {
                                if (writer.departmentConflict(run, scope, root, department)) stats.conflicts++;
                            } else writer.department(run, scope, root, department);
                            stats.organizations++;
                        }
                    });
                } catch (JobFailure failure) { throw failure; }
                catch (RuntimeException failure) {
                    var recovered = reload(run); var recoveredCursor = DirectorySyncStore.decode(recovered.getCursor(), Cursor.class); var recoveredStats = DirectorySyncStore.decode(recovered.getStatistics(), Statistics.class);
                    failure(recoveredStats, "department-write", root.externalDepartmentId(), recoveredCursor.page); recoveredCursor.root++; recoveredCursor.page = null; commit(context, recovered, recoveredCursor, recoveredStats, () -> { });
                }
            } else {
                if (cursor.department == null) {
                    Criteria query = Criteria.of().eq("tenantId", run.getTenantId()).eq("runId", run.getId());
                    if (cursor.departmentRow != null) query.gt("id", cursor.departmentRow);
                    var next = store.departments.query(query, new PageRequest(0, 1), Sort.asc("id"));
                    if (next.isEmpty()) {
                        cursor.phase = "FINISHED"; run.setCompleted(stats.failedPages == 0 && stats.conflicts == 0); commit(context, run, cursor, stats, () -> { }); continue;
                    }
                    cursor.departmentRow = next.getFirst().getId(); cursor.department = next.getFirst().getExternalDepartmentId(); cursor.page = null;
                    commit(context, run, cursor, stats, () -> { }); continue;
                }
                ExternalDirectoryProtocol.Page<String> page;
                Map<String, ExternalDirectoryProtocol.Person> people = new LinkedHashMap<>();
                try {
                    page = read(() -> protocol.departmentPeople(provider, cursor.department, cursor.page, PAGE_SIZE));
                    for (String id : page.items()) {
                        var person = read(() -> protocol.person(provider, id));
                        if (!id.equals(person.id())) throw new IllegalArgumentException("DIRECTORY_IDENTITY_MISMATCH"); people.put(id, person);
                    }
                } catch (JobFailure failure) { throw failure; }
                catch (RuntimeException failure) { failure(stats, "people", cursor.department, cursor.page); cursor.department = null; cursor.page = null; commit(context, run, cursor, stats, () -> { }); continue; }
                if (page.nextCursor() != null && page.nextCursor().equals(cursor.page)) throw new JobFailure("DIRECTORY_CURSOR_NOT_ADVANCING", false);
                Cursor after = DirectorySyncStore.decode(DirectorySyncStore.encode(cursor), Cursor.class); after.page = page.nextCursor(); if (after.page == null) after.department = null;
                var currentRun = run; var currentStats = stats;
                try {
                    commit(context, run, after, stats, () -> {
                        providers.requireDirectoryAuthority(context.tenantId(), provider);
                        for (String id : page.items()) {
                            if (!store.seen.query(Criteria.of().eq("tenantId", currentRun.getTenantId()).eq("runId", currentRun.getId()).eq("externalUserId", id), new PageRequest(0, 1)).isEmpty()) { currentStats.duplicates++; continue; }
                            var seen = new DirectorySyncSeen(); seen.setTenantId(currentRun.getTenantId()); seen.setRunId(currentRun.getId()); seen.setExternalUserId(id); DirectorySyncStore.insert(store.seen, seen);
                            currentStats.people++;
                            if (!writer.person(currentRun, scope, provider, people.get(id))) currentStats.conflicts++;
                        }
                    });
                } catch (JobFailure failure) { throw failure; }
                catch (RuntimeException failure) {
                    var recovered = reload(run); var recoveredCursor = DirectorySyncStore.decode(recovered.getCursor(), Cursor.class); var recoveredStats = DirectorySyncStore.decode(recovered.getStatistics(), Statistics.class);
                    failure(recoveredStats, "people-write", recoveredCursor.department, recoveredCursor.page); recoveredCursor.department = null; recoveredCursor.page = null; commit(context, recovered, recoveredCursor, recoveredStats, () -> { });
                }
            }
            context.progress(50);
        }
    }
    private static <T> T read(java.util.function.Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            try { return request.get(); }
            catch (IllegalArgumentException | JobFailure permanent) { throw permanent; }
            catch (RuntimeException unavailable) {
                if (attempt == 2) throw unavailable;
                try { Thread.sleep(50L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new JobFailure("DIRECTORY_READ_INTERRUPTED", false); }
            }
        }
    }
    private DirectorySyncRun reload(DirectorySyncRun run) { return store.runs.findById(run.getId()); }
    private static void failure(Statistics stats, String phase, String id, String cursor) {
        stats.failedPages++;
        // Store only page references, never provider messages, credentials or response bodies.
        if (stats.failures.size() < 100) stats.failures.add(phase + ":" + id + ":" + (cursor == null ? "0" : cursor));
    }
    private void commit(JobContext context, DirectorySyncRun run, Cursor cursor, Statistics stats, Runnable writes) {
        context.transaction(() -> lockBusiness(context.parameters()), () -> {
            writes.run(); run.setCursor(DirectorySyncStore.encode(cursor)); run.setStatistics(DirectorySyncStore.encode(stats)); run.setFailedPages(DirectorySyncStore.encode(stats.failures));
            DirectorySyncStore.save(store.runs, run); return null;
        });
    }
}
