package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

public abstract class StandardBusinessService<T extends EntityContract> extends AbstractAbilityService<T> {
    protected StandardBusinessService(String moduleAlias, Class<T> modelClass, BaseDao<T, String> dao) {
        super(moduleAlias, modelClass, dao);
    }

    @Override
    public void beforeInsert(T entity) {
        validateBeforeSave(entity);
        validateBeforeInsert(entity);
    }

    @Override
    public void beforeUpdate(T entity) {
        validateBeforeSave(entity);
    }

    @Override
    public void beforeUpdate(T entity, T existing) {
        beforeUpdate(entity);
        validateBeforeUpdate(entity, existing);
    }

    protected void validateBeforeSave(T entity) {
    }

    protected void validateBeforeInsert(T entity) {
    }

    /**
     * Runs after common save validation. Existing is a read-only RAW storage snapshot and may be null;
     * use the field-protection helpers when comparing or retaining protected values.
     */
    protected void validateBeforeUpdate(T entity, T existing) {
    }
}
