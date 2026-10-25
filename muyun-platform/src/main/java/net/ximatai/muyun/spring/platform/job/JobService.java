package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.notification.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Stable topic facade and clustered execution state machine. Delivery is at least once. */
public final class JobService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JobStore store;
    private final TransactionTemplate transactions;
    private final JobRegistry registry;
    private final ObjectProvider<JobFailureRecipientResolver> recipients;
    private final ObjectProvider<BusinessNotificationDelivery> notifications;
    private final ObjectProvider<JobManagementAccess> management;
    private final Duration defaultLease;
    private final int defaultRetentionDays;
    private volatile Consumer<String> wakeup = channel -> { };

    public JobService(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> database,
            PlatformTransactionManager transactions, JobRegistry registry,
            ObjectProvider<JobFailureRecipientResolver> recipients, ObjectProvider<BusinessNotificationDelivery> notifications,
            ObjectProvider<JobManagementAccess> management, Duration defaultLease, int defaultRetentionDays) {
        this.store = new JobStore(plans, runs, database, registry); this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setTimeout(30); this.registry = Objects.requireNonNull(registry); this.recipients = Objects.requireNonNull(recipients);
        this.notifications = Objects.requireNonNull(notifications); this.management = Objects.requireNonNull(management);
        if (defaultLease == null || defaultLease.compareTo(Duration.ofSeconds(30)) < 0 || defaultLease.compareTo(Duration.ofHours(1)) > 0
                || defaultRetentionDays < 1 || defaultRetentionDays > 36500)
            throw new IllegalArgumentException("Invalid default job policy");
        this.defaultLease = defaultLease; this.defaultRetentionDays = defaultRetentionDays;
    }
    public void onEnqueued(Consumer<String> listener) { wakeup = Objects.requireNonNull(listener); }

    /** Default registration preserves operator changes, including pause and schedule overrides. */
    public void registerDefaults() {
        system(() -> {
            for (var type : registry.definitions()) if (type.defaultSchedule() != null) {
                tx(() -> {
                    String key = JobKeys.effective(type.code(), type.scope(), null, "default"); store.partition(key);
                    if (store.plans.query(Criteria.of().eq("planKey", key), PageRequest.of(1, 1)).isEmpty()) {
                        var plan = new JobPlan(); plan.setJobType(type.code()); plan.setScope(type.scope().name());
                        plan.setObjectKey("default"); plan.setPlanKey(key); type.defaultSchedule().apply(plan);
                        initialize(plan, Map.of(), store.now()); store.insert(plan);
                    }
                    return null;
                });
            }
            int page = 1;
            while (true) {
                var plans = store.plans.query(Criteria.of(), PageRequest.of(page++, 200), Sort.asc("id"));
                for (var candidate : plans) if (!Objects.equals(candidate.getAvailable(), registry.contains(candidate.getJobType())))
                    tx(() -> { var row = store.plan(candidate.getId());
                        if (row != null) { row.setAvailable(registry.contains(row.getJobType())); store.save(row); } return null; });
                if (plans.size() < 200) break;
            }
            return null;
        });
    }

    /** Topic-owned plans may only be changed through this trusted Java facade. */
    public JobPlan registerManaged(String owner, JobPlan request, Map<String, Object> input) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Job managed owner required");
        requireScope(request.getTenantId());
        return system(() -> savePlan(request, input, owner, true));
    }
    /** Delete a topic-owned plan while preserving its immutable execution history. */
    public void deleteManaged(String owner, String id, String tenantId) {
        requireScope(tenantId);
        system(() -> tx(() -> {
            var row = store.plan(id); requireFound(row);
            if (!Objects.equals(owner, row.getManagedBy()) || !Objects.equals(tenantId, row.getTenantId()))
                throw new PlatformAccessDeniedException("Job topic ownership mismatch");
            store.plans.deleteById(id); return null;
        }));
    }
    public JobPlan configure(JobPlan request) {
        requireFound(request.getId());
        var original = store.plans.findById(request.getId()); requireFound(original);
        requireAdministrator(original.getTenantId());
        if (!Objects.equals(request.getTenantId(), original.getTenantId())
                || !Objects.equals(request.getJobType(), original.getJobType())
                || !Objects.equals(request.getObjectKey(), original.getObjectKey()))
            throw new IllegalArgumentException("Job plan ownership cannot change");
        return system(() -> savePlan(request, null, null, false));
    }
    private JobPlan savePlan(JobPlan request, Map<String, Object> input, String owner, boolean trusted) {
        var type = registry.definition(request.getJobType());
        if (request.getObjectKey() == null || request.getObjectKey().length() > 512) throw new IllegalArgumentException("Invalid plan object key");
        String key = JobKeys.effective(type.code(), type.scope(), request.getTenantId(), request.getObjectKey());
        JobSchedule schedule = JobSchedule.of(request);
        return tx(() -> {
            store.partition(key);
            var existing = store.plans.query(Criteria.of().eq("planKey", key), PageRequest.of(1, 1));
            JobPlan row = existing.isEmpty() ? null : store.plan(existing.getFirst().getId());
            if (request.getId() != null && (row == null || !request.getId().equals(row.getId())))
                throw new IllegalArgumentException("Plan identity cannot change");
            if (row != null && (!trusted && row.getManagedBy() != null || trusted && !Objects.equals(owner, row.getManagedBy())))
                throw new PlatformAccessDeniedException("Topic-managed job configuration is read-only");
            // Scans update runtime state continuously, so administrators are checked against the configuration
            // revision only; the topic facade is the source of truth for managed plans and overwrites.
            if (row != null && !trusted && request.getConfigRevision() != null
                    && !Objects.equals(request.getConfigRevision(), row.getConfigRevision()))
                throw new OptimisticLockException("Job plan changed");
            Instant now = store.now();
            if (row == null) {
                row = new JobPlan(); row.setJobType(type.code()); row.setScope(type.scope().name()); row.setTenantId(request.getTenantId());
                row.setObjectKey(request.getObjectKey()); row.setPlanKey(key); row.setManagedBy(owner); schedule.apply(row);
                initialize(row, input == null ? Map.of() : input, now);
                if (trusted && request.getIdentityMode() != null) row.setIdentityMode(request.getIdentityMode());
                row.setEnabled(!Boolean.FALSE.equals(request.getEnabled())); store.insert(row);
            } else {
                boolean changed = !schedule.equals(JobSchedule.of(row)); schedule.apply(row);
                if (changed) row.setNextFireAt(schedule.first(now));
                String parameters = input == null ? row.getParametersJson() : encodeParameters(input);
                boolean enabled = request.getEnabled() == null ? row.getEnabled() : request.getEnabled();
                if (changed || !parameters.equals(row.getParametersJson()) || !Objects.equals(enabled, row.getEnabled()))
                    row.setConfigRevision(row.getConfigRevision() + 1);
                if (trusted && request.getIdentityMode() != null) row.setIdentityMode(request.getIdentityMode());
                row.setAvailable(true); row.setEnabled(enabled); row.setParametersJson(parameters); store.save(row);
            }
            return row;
        });
    }
    private void initialize(JobPlan row, Map<String, Object> input, Instant now) {
        row.setIdentityMode("SYSTEM"); row.setParametersJson(encodeParameters(input)); row.setEnabled(true); row.setAvailable(true);
        row.setConsecutiveFailures(0); row.setSkippedCount(0L); row.setConfigRevision(1); row.setNextFireAt(JobSchedule.of(row).first(now));
    }

    /** Enqueue joins the caller's transaction; deduplication keys must include the topic input revision. */
    public JobRun enqueue(String typeCode, String tenantId, Map<String, Object> input, String deduplicationKey) {
        requireScope(tenantId);
        var type = registry.definition(typeCode); String frozen = encodeParameters(input);
        String mutex = JobKeys.effective(type.code(), type.scope(), tenantId, type.topicKey().apply(parameters(frozen)));
        String dedup = deduplicationKey == null ? null : JobKeys.effective(type.code(), type.scope(), tenantId, deduplicationKey);
        String actor = actor();
        return system(() -> tx(() -> {
            if (dedup != null) {
                store.partition(dedup);
                var existing = store.open("deduplicationKey", dedup);
                if (!existing.isEmpty()) {
                    var row = existing.getFirst();
                    if (!Objects.equals(tenantId, row.getTenantId()) || !typeCode.equals(row.getJobType()))
                        throw new PlatformAccessDeniedException("Job deduplication ownership mismatch");
                    return row;
                }
            }
            JobRun run = newRun(type, tenantId, frozen, mutex, "QUEUE", actor);
            run.setDeduplicationKey(dedup); store.insert(run); signal(run.getChannel()); return run;
        }));
    }

    /** Each candidate is locked and reread; missed calendar ticks collapse into one run. */
    public void scan() {
        system(() -> {
            var codes = registry.definitions().stream().map(JobDefinition::code).toList();
            if (codes.isEmpty()) return null;
            for (var candidate : store.plans.query(Criteria.of().eq("enabled", true).in("jobType", codes)
                    .lte("nextFireAt", store.now()), PageRequest.of(1, 1000), Sort.asc("nextFireAt"))) {
                tx(() -> {
                    var plan = store.plan(candidate.getId());
                    if (plan == null || !Boolean.TRUE.equals(plan.getEnabled())) return null;
                    boolean available = registry.contains(plan.getJobType());
                    if (!Objects.equals(plan.getAvailable(), available)) { plan.setAvailable(available); store.save(plan); }
                    Instant now = store.now();
                    if (!available || plan.getNextFireAt() == null || plan.getNextFireAt().isAfter(now)) return null;
                    if (!store.open("planId", plan.getId()).isEmpty()) {
                        plan.setSkippedCount(plan.getSkippedCount() + 1);
                    } else {
                        var type = registry.definition(plan.getJobType());
                        JobRun run = fromPlan(plan, type, "SCHEDULE", null); run.setScheduledAt(plan.getNextFireAt());
                        store.insert(run); plan.setLastStatus("PENDING"); signal(run.getChannel());
                    }
                    plan.setNextFireAt("FIXED_DELAY".equals(plan.getScheduleKind()) ? null : JobSchedule.of(plan).next(now));
                    store.save(plan); return null;
                });
            }
            return null;
        });
    }
    public JobRun runNow(String planId) {
        var requested = store.plans.findById(planId); requireFound(requested); requireAdministrator(requested.getTenantId());
        String actor = actor();
        return system(() -> tx(() -> {
            var plan = store.plan(planId); requireFound(plan);
            var type = registry.definition(plan.getJobType());
            if (!type.manualRetry()) throw new PlatformAccessDeniedException("Manual job execution disabled");
            if (!store.open("planId", planId).isEmpty()) throw new IllegalStateException("Plan has unfinished run");
            var run = fromPlan(plan, type, "MANUAL", actor); store.insert(run); plan.setLastStatus("PENDING"); store.save(plan); signal(run.getChannel()); return run;
        }));
    }
    public void pause(String planId) { enabled(planId, false); }
    public void resume(String planId) { enabled(planId, true); }
    private void enabled(String id, boolean enabled) {
        var requested = store.plans.findById(id); requireFound(requested); requireAdministrator(requested.getTenantId());
        var operator = CurrentUserContext.currentUser();
        registry.handler(requested.getJobType()).requirePlanChange(requested);
        system(() -> tx(() -> {
            var row = store.plan(id); requireFound(row);
            try (var actor = operator.isPresent() ? CurrentUserContext.use(operator.get()) : CurrentUserContext.without()) {
                registry.handler(row.getJobType()).requirePlanChange(row);
            }
            if (!Objects.equals(row.getEnabled(), enabled)) row.setConfigRevision(row.getConfigRevision() + 1);
            row.setEnabled(enabled);
            if (enabled) row.setNextFireAt(JobSchedule.of(row).first(store.now())); store.save(row);
            registry.handler(row.getJobType()).planStateChanged(row); return null;
        }));
    }
    public void cancel(String id) {
        var requested = store.runs.findById(id); requireFound(requested); requireAdministrator(requested.getTenantId());
        system(() -> tx(() -> {
            var row = store.run(id); requireFound(row);
            if (JobStore.OPEN.contains(row.getStatus())) {
                row.setCancelRequested(true);
                if (!"RUNNING".equals(row.getStatus())) finish(row, "CANCELLED", "CANCELLED", null);
                else store.save(row);
            }
            return null;
        }));
    }
    /** A manual retry creates a new run with the original immutable execution input and identity. */
    public JobRun retry(String id) {
        var requested = store.runs.findById(id); requireFound(requested); requireAdministrator(requested.getTenantId());
        String actor = actor();
        return system(() -> tx(() -> {
            var original = store.run(id); requireFound(original);
            var type = registry.definition(original.getJobType());
            if (!type.manualRetry() || !"FAILED".equals(original.getStatus())) throw new IllegalStateException("Run cannot be retried");
            if (original.getDeduplicationKey() != null) {
                store.partition(original.getDeduplicationKey());
                var existing = store.open("deduplicationKey", original.getDeduplicationKey());
                if (!existing.isEmpty()) {
                    var row = existing.getFirst();
                    if (!Objects.equals(original.getTenantId(), row.getTenantId()) || !original.getJobType().equals(row.getJobType()))
                        throw new PlatformAccessDeniedException("Job retry ownership mismatch");
                    return row;
                }
            }
            var run = newRun(type, original.getTenantId(), original.getParametersJson(), original.getMutexKey(), "MANUAL", actor);
            run.setPlanId(original.getPlanId()); run.setPlanRevision(original.getPlanRevision());
            run.setIdentityMode(original.getIdentityMode()); run.setDeduplicationKey(original.getDeduplicationKey());
            store.insert(run); recentStatus(run, "PENDING"); signal(run.getChannel()); return run;
        }));
    }

    JobRun claim(String channel, String nodeId) {
        return system(() -> {
            var codes = registry.definitions().stream().filter(type -> type.channel().equals(channel)).map(JobDefinition::code).toList();
            if (codes.isEmpty()) return null;
            String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
            List<Object> bindings = new ArrayList<>(); bindings.add(channel); bindings.addAll(codes);
            var candidates = store.database.query("""
                    select r.id, r.mutex_key from platform_job_run r
                    where r.channel = ? and r.job_type in (""" + placeholders + """
                    ) and ((r.status in ('PENDING','RETRY') and r.available_at <= clock_timestamp())
                        or (r.status = 'RUNNING' and r.lease_until <= clock_timestamp()))
                    and not exists (select 1 from platform_job_run active
                        where active.mutex_key = r.mutex_key and active.status = 'RUNNING'
                            and active.lease_until > clock_timestamp())
                    order by r.available_at, r.id limit 100
                    """, bindings);
            for (var candidate : candidates) {
                JobRun claimed = tx(() -> {
                    store.partition((String) candidate.get("mutex_key"));
                    store.lockBusiness((String) candidate.get("id"));
                    // A different run with this key may still hold its fencing row in a protected write.
                    store.database.query("select id from platform_job_run where mutex_key = ? and status = 'RUNNING' order by id for update", candidate.get("mutex_key"));
                    var row = store.run((String) candidate.get("id")); Instant now = store.now();
                    if (row == null || !JobStore.OPEN.contains(row.getStatus())) return null;
                    var type = registry.definition(row.getJobType());
                    if ("RUNNING".equals(row.getStatus()) && row.getLeaseUntil().isAfter(now)) return null;
                    if (!"RUNNING".equals(row.getStatus()) && row.getAvailableAt().isAfter(now)) return null;
                    if (Boolean.TRUE.equals(row.getCancelRequested())) { finish(row, "CANCELLED", "CANCELLED", null); return null; }
                    if (row.getAttempts() >= type.maximumAttempts()) { finish(row, "FAILED", "ATTEMPTS_EXHAUSTED", null); return null; }
                    if (!store.runs.query(Criteria.of().eq("mutexKey", row.getMutexKey()).eq("status", "RUNNING")
                            .gt("leaseUntil", now), PageRequest.of(1, 1)).isEmpty()) return null;
                    row.setStatus("RUNNING"); row.setAttempts(row.getAttempts() + 1); row.setNodeId(nodeId);
                    row.setStartedAt(now); if (row.getFirstStartedAt() == null) row.setFirstStartedAt(now);
                    row.setLeaseToken(Ids.newId()); row.setLeaseUntil(min(now.plus(lease(type)), now.plus(type.maximumRuntime())));
                    row.setFinishedAt(null); row.setErrorSummary(null); store.save(row); recentStatus(row, "RUNNING"); return row;
                });
                if (claimed != null) return claimed;
            }
            return null;
        });
    }
    public boolean runOne(String channel, String nodeId) { return runOne(channel, nodeId, () -> true); }
    /** The acceptance check fences claims that commit after local shutdown has begun. */
    public boolean runOne(String channel, String nodeId, BooleanSupplier accepting) {
        JobRun run = claim(channel, nodeId); if (run == null) return false;
        if (!accepting.getAsBoolean()) { releaseNode(nodeId); return false; }
        try (var user = CurrentUserContext.without(); var action = ActionExecutionContextHolder.without();
             var acting = ActingContextHolder.without(); var trace = RequestTraceContext.use(run.getTraceId());
             var execution = JobExecutionContext.use(new JobExecutionContext.Reference(run.getJobType(), run.getPlanId(), run.getId(), run.getIdentityMode()));
             var tenant = run.getTenantId() == null ? TenantContext.backgroundJob(run.getJobType())
                     : TenantContext.tenantSystem(run.getTenantId(), run.getJobType())) {
            if (!registry.handler(run.getJobType()).supportsIdentityMode(run.getIdentityMode())) throw new JobFailure("IDENTITY_MODE_UNSUPPORTED", false);
            JobResult result = Objects.requireNonNull(registry.handler(run.getJobType()).execute(new JobContext(this, run)));
            system(() -> tx(() -> {
                var row = store.leased(run.getId(), run.getLeaseToken());
                finish(row, Boolean.TRUE.equals(row.getCancelRequested()) ? "CANCELLED" : result.partial() ? "PARTIAL" : "SUCCEEDED",
                        result.code(), encode(result.statistics())); return null;
            }));
        } catch (Exception failure) {
            system(() -> tx(() -> {
                var row = store.run(run.getId());
                if (row != null && "RUNNING".equals(row.getStatus()) && Objects.equals(row.getLeaseToken(), run.getLeaseToken())) {
                    if (row.getLeaseUntil().isAfter(store.now())) failed(row, failure);
                }
                return null;
            }));
        }
        return true;
    }
    public void renewNode(String nodeId) {
        system(() -> {
            for (var candidate : store.runs.query(Criteria.of().eq("nodeId", nodeId).eq("status", "RUNNING"), PageRequest.of(1, 1000))) {
                tx(() -> {
                    var row = store.run(candidate.getId()); Instant now = store.now();
                    if (row == null || !"RUNNING".equals(row.getStatus()) || !nodeId.equals(row.getNodeId())) return null;
                    var type = registry.definition(row.getJobType());
                    Instant deadline = row.getStartedAt().plus(type.maximumRuntime());
                    if (!deadline.isAfter(now)) failed(row, new JobFailure("MAXIMUM_RUNTIME_EXCEEDED", true));
                    else if (row.getLeaseUntil().isAfter(now)) { row.setLeaseUntil(min(now.plus(lease(type)), deadline)); store.save(row); }
                    return null;
                });
            }
            return null;
        });
    }
    public void releaseNode(String nodeId) {
        system(() -> {
            for (var candidate : store.runs.query(Criteria.of().eq("nodeId", nodeId).eq("status", "RUNNING"), PageRequest.of(1, 1000))) {
                tx(() -> {
                    var row = store.run(candidate.getId());
                    if (row != null && "RUNNING".equals(row.getStatus()) && nodeId.equals(row.getNodeId())) {
                        row.setStatus("PENDING"); row.setAttempts(Math.max(0, row.getAttempts() - 1)); clearLease(row);
                        row.setAvailableAt(store.now()); store.save(row); recentStatus(row, "PENDING");
                    }
                    return null;
                });
            }
            return null;
        });
    }
    <T> T protectedTransaction(String id, String token, Runnable locks, Supplier<T> writes) {
        return tx(() -> { locks.run(); store.leased(id, token); return writes.get(); });
    }
    boolean cancelled(String id, String token) {
        return system(() -> tx(() -> Boolean.TRUE.equals(store.leased(id, token).getCancelRequested())));
    }
    void progress(String id, String token, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Progress must be between 0 and 100");
        system(() -> tx(() -> { var row = store.leased(id, token); row.setProgress(value); store.save(row); return null; }));
    }
    private void failed(JobRun row, Exception failure) {
        boolean cancelled = Boolean.TRUE.equals(row.getCancelRequested()) || failure instanceof JobFailure f && "CANCELLED".equals(f.code());
        if (cancelled) { finish(row, "CANCELLED", "CANCELLED", null); return; }
        var type = registry.definition(row.getJobType());
        String code = failure instanceof JobFailure f ? f.code() : "EXECUTION_FAILED";
        row.setErrorSummary(failure.getClass().getSimpleName() + ":" + code);
        boolean retryable = !(failure instanceof JobFailure f) || f.retryable();
        if (retryable && row.getAttempts() < type.maximumAttempts()) {
            row.setStatus("RETRY"); row.setResultCode(code); row.setAvailableAt(store.now().plus(type.backoff(row.getAttempts())));
            clearLease(row); store.save(row); recentStatus(row, "RETRY"); signal(row.getChannel());
        } else finish(row, "FAILED", code, null);
    }
    private void recentStatus(JobRun run, String status) {
        if (run.getPlanId() == null) return;
        var plan = store.plan(run.getPlanId());
        if (plan != null) { plan.setLastStatus(status); store.save(plan); }
    }
    private void finish(JobRun row, String status, String code, String statistics) {
        Instant now = store.now(); row.setStatus(status); row.setResultCode(code); row.setStatisticsJson(statistics);
        row.setFinishedAt(now); if ("FAILED".equals(status) && row.getErrorSummary() == null) row.setErrorSummary(code);
        clearLease(row); store.save(row);
        int failures = 0;
        if (row.getPlanId() != null) {
            var plan = store.plan(row.getPlanId());
            if (plan != null) {
                failures = "FAILED".equals(status) ? plan.getConsecutiveFailures() + 1 : 0;
                plan.setConsecutiveFailures(failures); plan.setLastStatus(status);
                if ("FIXED_DELAY".equals(plan.getScheduleKind())) plan.setNextFireAt(JobSchedule.of(plan).next(now));
                store.save(plan);
            }
        }
        var type = registry.definition(row.getJobType());
        if (shouldNotify(type, status, failures)) afterCommit(() -> notifyFailure(row));
    }
    static boolean shouldNotify(JobDefinition type, String status, int failures) {
        return type.failureNotification() && ("FAILED".equals(status) || failures >= type.failureThreshold());
    }
    private void notifyFailure(JobRun run) {
        try {
            var resolver = recipients.getIfAvailable(); var service = notifications.getIfAvailable();
            if (resolver == null || service == null) {
                LoggerFactory.getLogger(JobService.class).warn("Background job failed: type={}, run={}", run.getJobType(), run.getId()); return;
            }
            var target = resolver.resolve(run.getTenantId());
            var initiators = registry.handler(run.getJobType()).failureUserIds(run);
            if (!initiators.isEmpty()) {
                if (target == null) target = BusinessNotificationRecipients.none();
                var users = new ArrayList<>(target.userIds()); users.addAll(initiators);
                target = new BusinessNotificationRecipients(target.systemWide(), target.tenantIds(), target.organizationIds(),
                        target.departmentIds(), target.positionIds(), users);
            }
            if (target == null || target.equals(BusinessNotificationRecipients.none())) {
                LoggerFactory.getLogger(JobService.class).warn("Background job failed without online recipient selector: type={}, run={}", run.getJobType(), run.getId()); return;
            }
            service.deliver(new BusinessNotification(Ids.newId(), "BACKGROUND_JOB_FAILED", "后台作业失败", null,
                    "后台作业运行失败，请查看运行详情", true, target,
                    List.of(new BusinessNotificationNavigateAction("view", "查看运行", "platform.job_run", run.getId(), "DETAIL", run.getTenantId() == null ? Map.of() : Map.of("tenantId", run.getTenantId()), false)),
                    BusinessNotificationTone.DANGER, Instant.now(), Map.of(
                            "title", net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.job.notification.failed", "后台作业失败"),
                            "content", net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.job.notification.content", "后台作业运行失败，请查看运行详情"),
                            "action/view/label", net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.job.notification.view", "查看运行")),
                    Map.of("jobType", run.getJobType(), "runId", run.getId())));
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(JobService.class).warn("Background job failure notification unavailable: run={}, error={}", run.getId(), unavailable.getClass().getSimpleName());
        }
    }
    public int purgeExpired() {
        int removed = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = purgeExpiredBatch(); removed += count; if (count < 200) break;
        }
        return removed;
    }
    /** One short batch, also used by the retention handler's fenced transactions. */
    public int purgeExpiredBatch() {
        return system(() -> tx(() -> {
            int deleted = 0;
            var overrides = registry.definitions().stream().filter(type -> type.retentionDays() != null).toList();
            StringBuilder retention = new StringBuilder();
            if (overrides.isEmpty()) retention.append(defaultRetentionDays);
            else {
                retention.append("case job_type ");
                for (var type : overrides) retention.append("when '").append(type.code()).append("' then ").append(type.retentionDays()).append(' ');
                retention.append("else ").append(defaultRetentionDays).append(" end");
            }
            var candidates = store.database.query("""
                    select id from (
                        select id, plan_id, finished_at, job_type,
                            row_number() over (partition by plan_id order by finished_at desc, id desc) as position
                        from platform_job_run where status in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')
                    ) history where (plan_id is null or position > 20)
                        and finished_at < clock_timestamp() - interval '1 day' * (""" + retention + ") order by finished_at, id limit 200");
            for (var candidate : candidates) {
                var row = store.run((String) candidate.get("id"));
                if (row != null && !JobStore.OPEN.contains(row.getStatus()) && row.getFinishedAt() != null)
                    deleted += store.runs.deleteById(row.getId());
            }
            return deleted;
        }));
    }
    /** Topic deletion joins its owning transaction; the topic business lock must already be held. */
    public void purgeUnfinished(String typeCode, String tenantId, String runId) {
        requireScope(tenantId);
        system(() -> tx(() -> {
            var row = store.run(runId);
            if (row != null) {
                if (!typeCode.equals(row.getJobType()) || !Objects.equals(tenantId, row.getTenantId()))
                    throw new PlatformAccessDeniedException("Job topic ownership mismatch");
                if (JobStore.OPEN.contains(row.getStatus())) store.runs.deleteById(runId);
            }
            return null;
        }));
    }

    private JobRun fromPlan(JobPlan plan, JobDefinition type, String trigger, String actor) {
        var run = newRun(type, plan.getTenantId(), plan.getParametersJson(),
                JobKeys.effective(type.code(), type.scope(), plan.getTenantId(), type.topicKey().apply(parameters(plan.getParametersJson()))), trigger, actor);
        run.setPlanId(plan.getId()); run.setPlanRevision(plan.getConfigRevision()); run.setIdentityMode(plan.getIdentityMode()); return run;
    }
    private JobRun newRun(JobDefinition type, String tenant, String input, String mutex, String trigger, String actor) {
        var row = new JobRun(); row.setJobType(type.code()); row.setScope(type.scope().name()); row.setTenantId(tenant);
        row.setIdentityMode("SYSTEM"); row.setParametersJson(input); row.setMutexKey(mutex); row.setChannel(type.channel());
        row.setTriggerType(trigger); row.setTriggeredBy(actor); row.setStatus("PENDING"); row.setAttempts(0);
        row.setAvailableAt(store.now()); row.setCancelRequested(false); row.setProgress(0); row.setTraceId(Ids.newId()); return row;
    }
    private Duration lease(JobDefinition type) { return type.leaseDuration() == null ? defaultLease : type.leaseDuration(); }
    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static void clearLease(JobRun row) { row.setLeaseToken(null); row.setLeaseUntil(null); row.setNodeId(null); }
    private void signal(String channel) { afterCommit(() -> wakeup.accept(channel)); }
    private static void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { work.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { work.run(); }
        });
    }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> { store.limits(); return work.get(); }); }
    private static <T> T system(Supplier<T> work) {
        try (var user = CurrentUserContext.without(); var tenant = TenantContext.system("后台作业控制");
             var bypass = TenantContext.bypassTenantFilter("后台作业控制面跨租户扫描")) { return work.get(); }
    }
    private void requireAdministrator(String tenant) {
        var access = management.getIfAvailable();
        if (access == null) throw new PlatformAccessDeniedException("Job management access resolver unavailable");
        access.requireAdministrator(tenant);
    }
    private static void requireScope(String tenant) {
        if (TenantContext.isSystem()) return;
        if (!TenantContext.hasContext() || !Objects.equals(TenantContext.currentTenantId().orElse(null), tenant))
            throw new PlatformAccessDeniedException("Job ownership must match execution tenant");
    }
    private static String actor() { return CurrentUserContext.currentUser().map(CurrentUser::userId).orElse(null); }
    private static void requireFound(Object row) { if (row == null) throw new IllegalArgumentException("Job record not found"); }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception invalid) { throw new IllegalArgumentException("Invalid job input", invalid); }
    }
    static String encodeParameters(Map<String, Object> input) {
        String encoded = encode(input == null ? Map.of() : input);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("Job input exceeds 16 KiB");
        parameters(encoded); return encoded;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> parameters(String value) {
        try { return (Map<String, Object>) freeze(JSON.readValue(value == null ? "{}" : value, new TypeReference<Map<String, Object>>() { })); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid frozen job input", invalid); }
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(); map.forEach((key, item) -> copy.put((String) key, freeze(item))); return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(JobService::freeze).toList());
        return value;
    }
}
