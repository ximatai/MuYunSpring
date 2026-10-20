package net.ximatai.muyun.spring.ability.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Opts a service into platform numbering for its model's BusinessCode declarations. */
public interface CodeAbility<T extends EntityContract> extends CrudAbility<T> {
    default String codeEntityAlias() {
        String alias = getModuleAlias();
        return alias.substring(alias.lastIndexOf('.') + 1);
    }

    /** Domain scope of the persisted business record, not the operator's current assignment. */
    default String codeOrganizationId(T record) {
        return record instanceof net.ximatai.muyun.spring.common.model.capability.DataScopeCapable scoped
                ? scoped.getAuthOrganizationId() : null;
    }
}
