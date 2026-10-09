package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

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
        if (pointer == null) throw new PlatformException("workflow task not found: " + id);
        instance(pointer.getInstanceId());
        WorkflowTask locked = WorkflowTenantScope.visible(tasks.findById(id));
        if (locked == null) throw new PlatformException("workflow task not found: " + id);
        if (!pointer.getInstanceId().equals(locked.getInstanceId())) {
            throw new PlatformException("workflow task instance changed: " + id);
        }
        return locked;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workflow mutation key is required");
        return value;
    }

    private static String part(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
}
