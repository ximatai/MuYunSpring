package net.ximatai.muyun.spring.platform.job;

/** Implementations must be idempotent; external effects have at-least-once semantics. */
public interface JobHandler {
    JobDefinition definition();
    JobResult execute(JobContext context) throws Exception;
}
