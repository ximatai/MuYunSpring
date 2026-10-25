package net.ximatai.muyun.spring.platform.job;

import java.util.Map;
import java.util.function.Supplier;

/** Immutable identity/input view. Mutable run state is owned exclusively by the platform. */
public final class JobContext {
    private final JobService service;
    private final String runId, token, tenantId, triggerType, identityMode;
    private final Map<String, Object> parameters;
    JobContext(JobService service, JobRun run) {
        this.service = service; runId = run.getId(); token = run.getLeaseToken(); tenantId = run.getTenantId();
        triggerType = run.getTriggerType(); identityMode = run.getIdentityMode(); parameters = JobService.parameters(run.getParametersJson());
    }
    public String runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String triggerType() { return triggerType; }
    public String identityMode() { return identityMode; }
    public Map<String, Object> parameters() { return parameters; }
    public boolean cancelled() { return service.cancelled(runId, token); }
    public void checkCancelled() { if (cancelled()) throw new JobFailure("CANCELLED", false); }
    public void progress(int value) { service.progress(runId, token, value); }
    public <T> T transaction(Supplier<T> writes) { return transaction(() -> { }, writes); }
    public <T> T transaction(Runnable businessLocks, Supplier<T> writes) {
        return service.protectedTransaction(runId, token, businessLocks, writes);
    }
}
