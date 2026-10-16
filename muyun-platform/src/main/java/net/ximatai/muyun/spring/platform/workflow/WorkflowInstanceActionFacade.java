package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WorkflowInstanceActionFacade {
    private final WorkflowInstanceActionService actionService;

    public WorkflowInstanceActionFacade(WorkflowInstanceActionService actionService) {
        this.actionService = actionService;
    }

    public WorkflowInstanceActionResult execute(String actionCode, WorkflowInstanceActionRequest request) {
        return switch (requireActionCode(actionCode)) {
            case "revoke" -> actionService.revoke(request);
            case "reset" -> actionService.reset(request);
            case "terminate" -> actionService.terminate(request);
            case "forceTerminate" -> actionService.forceTerminate(request);
            default -> throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-instance-action.unsupported-workflow-instance-action",
                    "unsupported workflow instance action: {value1}"), Map.of("value1", String.valueOf(actionCode)));
        };
    }

    private String requireActionCode(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-instance-action.enter-a-workflow-action-code",
                    "workflow action code must not be blank"));
        }
        return actionCode;
    }
}
