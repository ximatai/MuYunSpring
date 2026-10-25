package net.ximatai.muyun.spring.platform.job;

/** Implementations must be idempotent; external effects have at-least-once semantics. */
public interface JobHandler {
    JobDefinition definition();
    /** Acquire topic locks before any run-row lock, including maintenance and cancellation. */
    default void lockBusiness(java.util.Map<String, Object> parameters) { }
    /** Mirror operational transitions into topic facts in the same transaction, without external calls. */
    default void stateChanged(JobRun run) { }
    JobResult execute(JobContext context) throws Exception;
}
