package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

public record WorkflowActivationTarget(String nodeKey, String routeInstanceId) {
    public WorkflowActivationTarget {
        if (nodeKey == null || nodeKey.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-activation-target.enter-a-target-node-key",
                    "workflow activation target node key must not be blank"));
        }
    }

    public static WorkflowActivationTarget of(String nodeKey) {
        return new WorkflowActivationTarget(nodeKey, null);
    }
}
