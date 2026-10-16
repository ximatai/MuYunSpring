package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;

/** A persisted workflow transition changes the owning record's business projection and action rights. */
final class WorkflowMutationFacts {
    private WorkflowMutationFacts() {}

    static void recordChanged(WorkflowInstance instance) {
        recordChanged(instance.getModuleAlias(), instance.getRecordId());
    }

    static void recordChanged(String moduleAlias, String recordId) {
        MutationContextHolder.current().ifPresent(context -> context.record(DataChange.recordUpdated(moduleAlias, recordId)));
    }
}
