package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowTaskActionFacade {
    private final WorkflowTaskActionService actionService;
    private final WorkflowTaskActionAvailabilityService availabilityService;

    public WorkflowTaskActionFacade(WorkflowTaskActionService actionService,
                                    WorkflowTaskActionAvailabilityService availabilityService) {
        this.actionService = actionService;
        this.availabilityService = availabilityService;
    }

    public List<WorkflowTaskAvailableAction> availableActions(String taskId, String operatorId) {
        return availabilityService.availableActions(taskId, operatorId);
    }

    public WorkflowTaskActionResult execute(String actionCode, WorkflowTaskActionRequest request) {
        return switch (requireActionCode(actionCode)) {
            case "revokeApprove" -> actionService.revokeApprove(request);
            case "approve" -> actionService.approve(request);
            case "reject" -> actionService.reject(request);
            case "rollback" -> actionService.rollback(request);
            case "resubmit" -> actionService.resubmit(request);
            case "complete" -> actionService.completeBusinessTask(request);
            case "notice" -> actionService.notice(request);
            case "read" -> actionService.readNotice(request);
            case "transfer" -> actionService.transfer(request);
            case "addSign" -> actionService.addSign(request);
            case "invalidate" -> actionService.invalidate(request);
            case "cancel" -> actionService.cancel(request);
            default -> throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-task-action.unsupported-task-action",
                    "unsupported workflow task action: {value1}"), Map.of("value1", String.valueOf(actionCode)));
        };
    }

    private String requireActionCode(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-task-action.enter-a-workflow-action-code",
                    "workflow action code must not be blank"));
        }
        return actionCode;
    }
}
