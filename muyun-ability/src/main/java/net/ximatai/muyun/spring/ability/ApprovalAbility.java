package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.capability.ApprovalCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import java.util.Optional;

/** Narrow server-side summary command. The caller owns the workflow action authorization. */
public interface ApprovalAbility<T extends EntityContract & ApprovalCapable> extends CrudAbility<T> {
    default boolean supportsApproval() { return true; }

    /** Projects ordinary editor availability; trusted workflow commands retain their own authorization. */
    default Optional<RecordActionAvailabilityDecision> ordinaryApprovalRecordActionAvailability(String actionCode, T record) {
        return supportsApproval() && PlatformAction.UPDATE.matches(actionCode) && ApprovalMutationSupport.blocksOrdinaryUpdate(record)
                ? Optional.of(RecordActionAvailabilityDecision.unavailable(ApprovalMutationSupport.ORDINARY_UPDATE_BLOCKED))
                : Optional.empty();
    }

    default int writeApprovalState(String recordId, ActionExecutionPolicy policy, ApprovalState state) {
        return ApprovalMutationSupport.update(this, recordId, policy, state);
    }

    /** Trusted domain command after task/guide authorization. Normal permission and lifecycle checks still apply. */
    default int writeApprovalBusiness(T record) {
        return ApprovalMutationSupport.updateBusiness(this, record);
    }

    /** Detached business values without aggregate children; metadata adapters supply their own copy. */
    default T copyForApprovalMutation(T record) { return EntityRecordCopies.forFieldMutation(record); }
}
