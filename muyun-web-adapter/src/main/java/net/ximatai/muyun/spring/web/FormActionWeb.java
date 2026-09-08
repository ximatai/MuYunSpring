package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.web.PlatformWebPathRules;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Opt-in form execution; the adapter never persists a draft or delegates to a list action. */
public interface FormActionWeb<S, T extends EntityContract, R> extends ScopedWeb<S> {
    R executeFormAction(String actionCode, FormActionRequest<T> request);

    /** Adapters may delegate to their existing record-scope runtime; draft fields are never scope criteria. */
    default void requireFormRecordScope(T record, ActionExecutionPolicy policy) {
        if (!policy.requiresDataScope()) return;
        if (!(service() instanceof DataScopeAbility<?> dataScope)) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                    "form action requires record data scope support");
        }
        dataScope.requireRecordScope(policy, java.util.List.of(record.getId()));
    }

    @PostMapping("/form-actions/" + PlatformWebPathRules.ACTION_CODE_PATH)
    @CustomActionEndpoint(actionCodePathVariable = "actionCode", formContext = true)
    default R formAction(@PathVariable String actionCode, @RequestBody FormActionRequest<T> request) {
        if (request == null || request.record() == null) {
            throw new IllegalArgumentException("form action requires current record");
        }
        return webScope(() -> {
            // Resolve scope from the persisted record, never from editable draft fields.
            // Dynamic services retain their own action execution checks on the same request.
            T record = request.record();
            if (record.getId() != null && !record.getId().isBlank()) {
                var context = ActionExecutionContextHolder.current()
                        .filter(value -> value.moduleAlias().equals(webScopeName()) && value.actionCode().equals(actionCode))
                        .orElseThrow(() -> new IllegalStateException("form action requires authorized action context"));
                requireFormRecordScope(record, context.actionPolicy());
            }
            return executeFormAction(actionCode, request);
        });
    }
}
