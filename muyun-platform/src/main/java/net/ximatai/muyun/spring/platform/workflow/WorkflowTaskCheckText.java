package net.ximatai.muyun.spring.platform.workflow;

/** Frozen authored text declarations, independent of the current check verdict or failure evidence. */
public record WorkflowTaskCheckText(String checkKey, String title, String failureMessage) {
    public static WorkflowTaskCheckText from(WorkflowTaskCheck check) {
        return new WorkflowTaskCheckText(check.getCheckKey(), check.getTitle(), check.getFailureMessage());
    }
}
