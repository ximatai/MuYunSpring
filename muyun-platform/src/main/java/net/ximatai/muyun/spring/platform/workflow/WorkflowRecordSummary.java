package net.ximatai.muyun.spring.platform.workflow;

/** Business labels are output projections, never a second source of workflow authorization. */
public record WorkflowRecordSummary(String title, String moduleTitle, boolean readable) { }
