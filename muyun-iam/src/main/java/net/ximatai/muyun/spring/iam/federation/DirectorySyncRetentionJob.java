package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.platform.job.JobContext;
import net.ximatai.muyun.spring.platform.job.JobDefinition;
import net.ximatai.muyun.spring.platform.job.JobHandler;
import net.ximatai.muyun.spring.platform.job.JobResult;
import net.ximatai.muyun.spring.platform.job.JobSchedule;
import net.ximatai.muyun.spring.platform.job.JobScope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Bounded topic retention, preserving the latest complete authoritative observation and later rounds. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySyncRetentionJob implements JobHandler {
    public static final String TYPE = "iam.directory-sync-retention";
    private static final int BATCH_SIZE = 200;
    private static final int MAX_BATCHES = 10;
    private final IDatabaseOperations<?> database;
    private final DirectorySyncStore store;

    // Scopes survive policy deletion and identify the provider of historical rounds.
    // Queued manual retries still reference the old topic run until execution starts.
    private static final String ELIGIBLE = """
            from iam_directory_sync_run r
            join iam_directory_sync_scope s on s.id = r.scope_id and s.tenant_id = r.tenant_id
            where r.status in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')
              and r.updated_at < clock_timestamp() - interval '30 days'
              and not exists (
                select 1 from platform_job_run j
                where j.job_type = 'iam.directory-sync' and j.tenant_id = r.tenant_id
                  and j.status in ('PENDING','RETRY','RUNNING')
                  and (j.id = r.job_run_id or j.parameters_json::jsonb ->> 'syncRunId' = r.id))
              and (not exists (
                select 1 from iam_directory_sync_run c
                join iam_directory_sync_scope cs on cs.id = c.scope_id and cs.tenant_id = c.tenant_id
                where c.tenant_id = r.tenant_id and cs.provider_id = s.provider_id
                  and c.mode = 'SYNC' and c.completed = true and c.status = 'SUCCEEDED')
                or exists (
                select 1 from iam_directory_sync_run c
                join iam_directory_sync_scope cs on cs.id = c.scope_id and cs.tenant_id = c.tenant_id
                where c.tenant_id = r.tenant_id and cs.provider_id = s.provider_id
                  and c.mode = 'SYNC' and c.completed = true and c.status = 'SUCCEEDED'
                  and (c.created_at, c.id) > (r.created_at, r.id)))
            """;

    public DirectorySyncRetentionJob(IDatabaseOperations<?> database, DirectorySyncStore store) {
        this.database = Objects.requireNonNull(database, "database");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public JobDefinition definition() {
        return new JobDefinition(TYPE, "iam.job.type.directorySyncRetention", JobScope.PLATFORM,
                JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default",
                null, Duration.ofMinutes(5), 1, Duration.ZERO, input -> "default", true, 3, true, null);
    }

    @Override
    public JobResult execute(JobContext context) {
        long deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var candidates = database.query("select r.id, r.tenant_id, s.provider_id " + ELIGIBLE
                    + " order by r.updated_at, r.id limit 1");
            if (candidates.isEmpty()) {
                return JobResult.success("DIRECTORY_FACTS_CLEANED", Map.of("deleted", deleted));
            }
            var candidate = candidates.getFirst();
            String id = (String) candidate.get("id");
            Map<String, Object> lock = Map.of("tenantId", candidate.get("tenant_id"),
                    "providerId", candidate.get("provider_id"));
            deleted += context.transaction(() -> store.lock(lock), () -> {
                // Recheck after the topic lock: completion, retry or takeover may have won the race.
                if (database.query("select r.id " + ELIGIBLE + " and r.id = ?", id).isEmpty()) return 0;
                database.execute("update iam_directory_sync_run set retention_started = true where id = ?", id);
                int count = deleteChildren("iam_directory_sync_seen", id);
                count += deleteChildren("iam_directory_sync_department", id);
                count += database.execute("""
                        delete from iam_directory_sync_run where id = ?
                          and not exists (select 1 from iam_directory_sync_seen where run_id = ?)
                          and not exists (select 1 from iam_directory_sync_department where run_id = ?)
                        """, id, id, id);
                return count;
            });
        }
        return JobResult.partial("BATCH_LIMIT_REACHED", Map.of("deleted", deleted));
    }

    private int deleteChildren(String table, String runId) {
        return database.execute("delete from " + table + " where id in (select id from " + table
                + " where run_id = ? order by id limit " + BATCH_SIZE + ")", runId);
    }
}
