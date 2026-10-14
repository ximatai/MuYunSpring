package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

public record WorkflowModuleTaskEvaluation(
        WorkflowTaskCheckStatus checkStatus,
        boolean passed,
        String failureMessage,
        List<WorkflowTaskCheckResult> checkResults,
        List<WorkflowTaskGuide> guides,
        String failureCheckKey
) {
    /** Custom evaluators without an identified check keep their authored failure text unbound. */
    public WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus checkStatus, boolean passed, String failureMessage,
                                        List<WorkflowTaskCheckResult> checkResults, List<WorkflowTaskGuide> guides) {
        this(checkStatus, passed, failureMessage, checkResults, guides, null);
    }

    public WorkflowModuleTaskEvaluation {
        checkStatus = checkStatus == null ? WorkflowTaskCheckStatus.NOT_CHECKED : checkStatus;
        checkResults = checkResults == null ? List.of() : List.copyOf(checkResults);
        guides = guides == null ? List.of() : List.copyOf(guides);
    }

    public static WorkflowModuleTaskEvaluation passed(List<WorkflowTaskCheckResult> checkResults,
                                                      List<WorkflowTaskGuide> guides) {
        return new WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus.PASSED, true, null, checkResults, guides);
    }

    public static WorkflowModuleTaskEvaluation manualConfirm(List<WorkflowTaskGuide> guides) {
        return new WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus.NO_CHECK, true, null, List.of(), guides);
    }

    public static WorkflowModuleTaskEvaluation failedCheck(WorkflowTaskCheckResult check,
                                                           List<WorkflowTaskCheckResult> checkResults,
                                                           List<WorkflowTaskGuide> guides) {
        return new WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus.FAILED, false, check.getFailureMessage(),
                checkResults, guides, check.getCheckKey());
    }

    public static WorkflowModuleTaskEvaluation failed(String failureMessage,
                                                      List<WorkflowTaskCheckResult> checkResults,
                                                      List<WorkflowTaskGuide> guides) {
        return new WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus.FAILED, false, failureMessage,
                checkResults, guides);
    }
}
