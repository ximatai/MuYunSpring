package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Platform maintenance across all account partitions; no historical online lifecycle replay. */
@Component
public class UserSessionRetentionJob implements JobHandler {
    public static final String TYPE = "iam.user-session-retention";
    private final IDatabaseOperations<?> database;
    private final UserSessionRetentionPolicy policy;

    public UserSessionRetentionJob(IDatabaseOperations<?> database, UserSessionRetentionPolicy policy) {
        this.database = Objects.requireNonNull(database, "database");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public JobDefinition definition() {
        return new JobDefinition(TYPE, "iam.job.type.userSessionRetention", JobScope.PLATFORM,
                JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default", null,
                Duration.ofMinutes(5), 1, Duration.ZERO, input -> "default", true, 3, true, null);
    }

    @Override
    public JobResult execute(JobContext context) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        for (String key : new String[]{"deleted", "batches", "idleExpired", "absoluteExpired", "loggedOut", "revoked"})
            statistics.put(key, 0L);
        // Freeze the cutoff for this run. The database clock is shared by all cluster nodes.
        Object cutoff = database.query("select timezone('UTC', clock_timestamp()) - (? * interval '1 day') as cutoff",
                policy.retentionDays()).getFirst().get("cutoff");
        for (int batch = 0; batch < policy.maxBatches(); batch++) {
            var counts = context.transaction(() -> database.query("""
                    with candidates as materialized (
                        select id, tenant_id,
                          case when revoked_at is null or revoked_reason = 'session expired' then
                            case when max_expires_at <= expires_at then 'absoluteExpired' else 'idleExpired' end
                          when revoked_reason = 'logout' then 'loggedOut' else 'revoked' end as reason
                        from iam_user_session
                        where ((revoked_at is null or revoked_reason = 'session expired')
                                 and (expires_at <= ? or max_expires_at <= ?))
                           or (revoked_at is not null and revoked_reason is distinct from 'session expired'
                                 and revoked_at <= ?)
                        order by case when revoked_at is null or revoked_reason = 'session expired'
                                      then least(expires_at, max_expires_at) else revoked_at end, tenant_id, id
                        limit ? for update skip locked
                    ), removed as (
                        delete from iam_user_session s using candidates c
                        where s.id = c.id and s.tenant_id is not distinct from c.tenant_id
                        returning c.reason
                    )
                    select reason, count(*) as count from removed group by reason
                    """, cutoff, cutoff, cutoff, policy.batchSize()));
            long deleted = 0;
            for (var count : counts) {
                long value = ((Number) count.get("count")).longValue();
                statistics.merge((String) count.get("reason"), value, Long::sum);
                deleted += value;
            }
            statistics.merge("deleted", deleted, Long::sum);
            if (deleted > 0) statistics.merge("batches", 1L, Long::sum);
            if (deleted < policy.batchSize()) return JobResult.success("USER_SESSIONS_CLEANED", statistics);
        }
        return JobResult.partial("BATCH_LIMIT_REACHED", statistics);
    }
}
