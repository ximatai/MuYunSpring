package net.ximatai.muyun.spring.platform.job;

/** Implementations must be idempotent; external effects have at-least-once semantics. */
public interface JobHandler {
    JobDefinition definition();
    default boolean supportsIdentityMode(String mode) { return "SYSTEM".equals(mode); }
    /** Acquire topic locks before any run-row lock, including maintenance and cancellation. */
    default void lockBusiness(java.util.Map<String, Object> parameters) { }
    /** Mirror operational transitions into topic facts in the same transaction, without external calls. */
    default void stateChanged(JobRun run) { }
    /** Mirror operator pause/resume into topic presentation in the same transaction. */
    default void planStateChanged(JobPlan plan) { }
    /** Additional topic authority beyond the owning scope's administrator, checked as the real operator. */
    default void requirePlanChange(JobPlan plan) { }
    /** Frozen initiators to include in final-failure reminders; no permission snapshots. */
    default java.util.List<String> failureUserIds(JobRun run) { return java.util.List.of(); }
    /** Failure retry policy, separate from the attempt budget used when recovering abandoned leases. */
    default boolean retryAutomatically(JobRun run) { return true; }
    /** Topic continuation references for an operator retry; retain immutable original input and identity. */
    default java.util.Map<String, Object> retryParameters(java.util.Map<String, Object> original, String originalRunId) { return original; }
    JobResult execute(JobContext context) throws Exception;
}
