package net.ximatai.muyun.spring.platform.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.ApprovalAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Standard updates share normal field, tenant, permission, optimistic-lock and lifecycle contracts. */
@Service @Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultModuleRecordActionExecutor implements ModuleRecordActionExecutor {
    private final ObjectProvider<CrudAbility<?>> abilities;
    private final DynamicRecordService records;
    private final ActionExecutionPolicyService policies;
    private final ObjectMapper mapper;
    public DefaultModuleRecordActionExecutor(ObjectProvider<CrudAbility<?>> abilities, DynamicRecordService records,
                                            ActionExecutionPolicyService policies, ObjectMapper mapper) {
        this.abilities = abilities; this.records = records; this.policies = policies; this.mapper = mapper;
    }
    @Override public boolean supports(String moduleAlias, String actionCode) { return true; }
    @Override public Object execute(ModuleRecordActionCommand command) {
        return execute(command, false);
    }
    @Override public Object executeApprovalBusiness(ModuleRecordActionCommand command) {
        return execute(command, true);
    }
    private Object execute(ModuleRecordActionCommand command, boolean approvalBusiness) {
        var service = abilities.orderedStream().filter(item -> command.moduleAlias().equals(item.getModuleAlias())).findFirst();
        if (!"update".equals(command.actionCode())) {
            if (service.isPresent()) throw new PlatformException("静态业务动作未注册领域执行器: " + command.actionCode());
            return approvalBusiness
                    ? records.executeApprovalBusinessAction(command.moduleAlias(), command.actionCode(),
                        command.recordId(), command.payload())
                    : records.executeAction(command.moduleAlias(), command.actionCode(),
                        DynamicActionExecutionRequest.id(command.recordId()).withPayload(command.payload()));
        }
        if (command.version() == null) throw new PlatformException("业务保存必须携带记录版本");
        var context = ActionExecutionContext.ofPlatformAction(command.moduleAlias(), PlatformAction.UPDATE,
                Set.of(command.recordId()), CurrentUserContext.currentUser());
        policies.requireRecordAction(context);
        try (var action = ActionExecutionContextHolder.use(context)) {
            if (service.isPresent()) return updateStatic(service.get(), command, approvalBusiness);
            String entity = records.mainEntityAlias(command.moduleAlias());
            var stored = records.select(command.moduleAlias(), entity, command.recordId());
            if (stored == null) throw new PlatformException("业务记录不存在");
            var draft = stored.copy();
            for (var field : command.values().entrySet()) {
                if (!Set.of("id", "version", "tenantId", "createdAt", "createdBy", "updatedAt", "updatedBy", "approvalStatus", "approvalInstanceId", "approvalSubmittedBy", "approvalSubmittedAt", "approvalCompletedAt").contains(field.getKey()))
                    draft.setValue(field.getKey(), field.getValue());
            }
            draft.setId(command.recordId()); draft.setVersion(command.version());
            int count = approvalBusiness ? records.writeApprovalBusiness(command.moduleAlias(), entity, draft)
                    : records.update(command.moduleAlias(), entity, draft);
            if (count != 1) throw new PlatformException("业务数据保存失败");
            return records.select(command.moduleAlias(), entity, command.recordId());
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object updateStatic(CrudAbility ability, ModuleRecordActionCommand command, boolean approvalBusiness) {
        Object stored = ability.select(command.recordId());
        if (stored == null) throw new PlatformException("业务记录不存在");
        try {
            EntityContract draft = (EntityContract) mapper.convertValue(stored, ability.modelClass());
            mapper.updateValue(draft, command.values());
            draft.setId(command.recordId()); draft.setVersion(command.version());
            int count = approvalBusiness && ability instanceof ApprovalAbility approval && approval.supportsApproval()
                    ? approval.writeApprovalBusiness(draft) : ability.update(draft);
            if (count != 1) throw new PlatformException("业务数据保存失败");
            return ability.select(command.recordId());
        } catch (java.io.IOException failure) { throw new PlatformException("业务字段解析失败", failure); }
    }
}
