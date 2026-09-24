package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

public abstract class SystemStandardBusinessService<T extends EntityContract> extends StandardBusinessService<T>
        implements SystemManagedAbility<T> {
    protected SystemStandardBusinessService(String moduleAlias, Class<T> modelClass, BaseDao<T, String> dao) {
        super(moduleAlias, modelClass, dao);
    }

}
