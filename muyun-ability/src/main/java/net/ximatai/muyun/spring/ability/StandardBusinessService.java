package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/**
 * Standard static business Service with shared and operation-specific validation templates.
 *
 * <p>Override the protected validation methods for domain invariants while retaining the
 * normal CRUD lifecycle. Additional capabilities, including soft deletion and caching,
 * are composed explicitly. Tenant business data can use {@link TenantActiveScopedService}.</p>
 *
 * @param <T> business model with the platform entity contract
 */
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

    /** Validates domain rules common to insert and update before persistence. */
    protected void validateBeforeSave(T entity) {
    }

    /** Validates insert-only domain rules after common save validation. */
    protected void validateBeforeInsert(T entity) {
    }

    /**
     * Runs after common save validation. Existing is a read-only RAW storage snapshot and may be null;
     * use the field-protection helpers when comparing or retaining protected values.
     */
    protected void validateBeforeUpdate(T entity, T existing) {
    }
}
