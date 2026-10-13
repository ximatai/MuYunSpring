package net.ximatai.muyun.spring.platform.workflow;

/** Business labels are output projections, never a second source of workflow authorization. */
public record WorkflowRecordSummary(String title, String moduleTitle, boolean readable,
                                    net.ximatai.muyun.spring.common.i18n.TextReference moduleTitleSource) {
    public WorkflowRecordSummary(String title, String moduleTitle, boolean readable) { this(title, moduleTitle, readable, null); }
}
