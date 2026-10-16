package net.ximatai.muyun.spring.platform.workflow;

import java.util.Map;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Workflow partitions are serialized by the host until transaction completion. */
public final class WorkflowMutationLock {
    private WorkflowMutationLock() {}

    public static void record(String moduleAlias, String recordId) {
        PlatformAbilityRuntime.lockMutationPartition("platform.workflow.record",
                part(TenantContext.currentTenantId().orElse(null)) + part(require(moduleAlias)) + part(require(recordId)));
    }

    public static void instance(String instanceId) {
        PlatformAbilityRuntime.lockMutationPartition("platform.workflow.instance", require(instanceId));
    }

    /** Read the immutable instance pointer, lock, then read the task state used for every decision. */
    public static WorkflowTask task(WorkflowTaskDao tasks, String taskId) {
        String id = require(taskId);
        WorkflowTask pointer = WorkflowTenantScope.visible(tasks.findById(id));
        if (pointer == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-mutation-lock.approval-task-not-found",
                "workflow task not found: {value1}"), Map.of("value1", String.valueOf(id)));
        instance(pointer.getInstanceId());
        WorkflowTask locked = WorkflowTenantScope.visible(tasks.findById(id));
        if (locked == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-mutation-lock.approval-task-not-found",
                "workflow task not found: {value1}"), Map.of("value1", String.valueOf(id)));
        if (!pointer.getInstanceId().equals(locked.getInstanceId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-mutation-lock.the-task-s-workflow-changed-reload-and-try-again",
                    "workflow task instance changed: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return locked;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workflow mutation key is required");
        return value;
    }

    private static String part(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
}
