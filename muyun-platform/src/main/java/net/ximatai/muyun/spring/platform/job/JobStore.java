package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import java.time.Instant;
import java.util.*;

/** Internal state access. Row locks and the database clock share the caller's transaction. */
final class JobStore {
    final JobPlanDao plans;
    final JobRunDao runs;
    final IDatabaseOperations<?> database;
    static final List<String> OPEN = List.of("PENDING", "RETRY", "RUNNING");
    JobStore(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> database) {
        this.plans = Objects.requireNonNull(plans); this.runs = Objects.requireNonNull(runs); this.database = Objects.requireNonNull(database);
    }
    Instant now() {
        Number seconds = (Number) database.row("select extract(epoch from clock_timestamp()) as seconds").get("seconds");
        return Instant.ofEpochMilli(new java.math.BigDecimal(seconds.toString()).movePointRight(3).longValue());
    }
    void limits() {
        requireTransaction();
        database.query("select set_config('statement_timeout', '20000', true) as statement_timeout, "
                + "set_config('lock_timeout', '10000', true) as lock_timeout");
    }
    void partition(String key) {
        requireTransaction();
        database.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", "platform.job:" + key);
    }
    JobPlan plan(String id) {
        requireTransaction(); database.query("select id from platform_job_plan where id = ? for update", id);
        return plans.findById(id);
    }
    JobRun run(String id) {
        requireTransaction(); database.query("select id from platform_job_run where id = ? for update", id);
        return runs.findById(id);
    }
    JobRun leased(String id, String token) {
        JobRun row = run(id);
        Instant now = now();
        if (row == null || !"RUNNING".equals(row.getStatus()) || !Objects.equals(token, row.getLeaseToken())
                || row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(now))
            throw new JobFailure("LEASE_LOST", false);
        return row;
    }
    void insert(JobPlan row) { EntityLifecycle.prepareInsert(row, now()); plans.insert(row); }
    void insert(JobRun row) { EntityLifecycle.prepareInsert(row, now()); runs.insert(row); }
    void save(JobPlan row) {
        int version = row.getVersion(); EntityLifecycle.prepareUpdate(row, now());
        if (plans.updateByIdAndVersion(row, version) != 1) throw new OptimisticLockException("Job plan changed");
    }
    void save(JobRun row) {
        int version = row.getVersion(); EntityLifecycle.prepareUpdate(row, now());
        if (runs.updateByIdAndVersion(row, version) != 1) throw new OptimisticLockException("Job run changed");
    }
    List<JobRun> open(String field, String key) {
        return runs.query(Criteria.of().eq(field, key).in("status", OPEN), PageRequest.of(1, 1));
    }
    private static void requireTransaction() {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Job state requires a transaction");
    }
}
