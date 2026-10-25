/**
 * Durable platform background jobs. Topics implement {@link net.ximatai.muyun.spring.platform.job.JobHandler}
 * and enter through {@link net.ximatai.muyun.spring.platform.job.JobService}; runtime state belongs to the platform.
 * Inputs carry references and revisions, never credentials or business documents. Execution is at least once.
 * Business writes use the context's fenced transaction with topic locks before the run lock.
 */
package net.ximatai.muyun.spring.platform.job;
