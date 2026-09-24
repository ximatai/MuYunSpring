package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.RelatedRecordDeletion;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** Resolves the effective source action and delegates owned-record deletion to the shared lifecycle. */
@Service
public class RelatedRecordDeletionService {
    private final PlatformModuleActionService actions;
    private final ObjectProvider<ActionExecutionPolicyService> authorization;

    public RelatedRecordDeletionService(PlatformModuleActionService actions,
                                        ObjectProvider<ActionExecutionPolicyService> authorization) {
        this.actions = actions;
        this.authorization = authorization;
    }

    public <S extends EntityContract, B extends EntityContract, T extends EntityContract>
    RelatedRecordDeletion.Result<B> delete(RelatedRecordDeletion<S, B, T> relation, String ownerId,
                                           String action, Supplier<String> bindingIdLookup) {
        return relation.delete(ownerId, bindingIdLookup,
                actions.requireExecutionPolicy(relation.source().getModuleAlias(), action), authorization.getObject());
    }
}
