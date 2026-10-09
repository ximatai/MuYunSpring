package net.ximatai.muyun.spring.platform.workflow;

public interface WorkflowRecordSummaryResolver {
    WorkflowRecordSummaryResolver NONE = instance -> null;
    default java.util.Map<String, String> modules() { return java.util.Map.of(); }

    WorkflowRecordSummary resolve(WorkflowInstance instance);
}
