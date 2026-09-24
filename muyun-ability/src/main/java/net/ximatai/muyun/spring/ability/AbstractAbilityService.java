package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.util.Preconditions;

import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class AbstractAbilityService<T extends EntityContract> implements CrudAbility<T> {
    private final String moduleAlias;
    private final Class<T> modelClass;
    private final BaseDao<T, String> dao;

    protected AbstractAbilityService(String moduleAlias, Class<T> modelClass, BaseDao<T, String> dao) {
        this.moduleAlias = Preconditions.requireText(moduleAlias, "moduleAlias");
        this.modelClass = Objects.requireNonNull(modelClass, "modelClass must not be null");
        this.dao = Objects.requireNonNull(dao, "dao must not be null");
    }

    // Accessors must remain interceptable so class proxies delegate to their initialized target.
    @Override
    public BaseDao<T, String> getDao() {
        return dao;
    }

    @Override
    public String getModuleAlias() {
        return moduleAlias;
    }

    @Override
    public Class<T> modelClass() {
        return modelClass;
    }

    /**
     * Executes a domain command through normal polymorphic update, preserving actor, scope,
     * version and lifecycle. Fields are server-owned declarations, never request field names.
     * The callback receives detached business values; only declared fields are copied back.
     * Aggregate child collections do not participate in field commands.
     */
    protected final int mutateFields(ActionExecutionPolicy policy,
                                     String id, Consumer<T> mutation, String... fields) {
        return RecordFieldMutation.update(this, policy, id, mutation, Set.of(fields));
    }

    /** Retains RAW snapshot values in business form, including decryption/verification; an exact field command may update them. */
    protected final void retainCommandFields(T incoming, T existing, String... fields) {
        RecordFieldMutation.retain(this, incoming, existing, fields);
    }

    protected final boolean existsOtherInCurrentScope(T entity, Criteria criteria) {
        String currentId = entity == null ? null : entity.getId();
        return list(criteria, PageRequest.of(1, 2)).stream()
                .anyMatch(existing -> !Objects.equals(existing.getId(), currentId));
    }

    protected final T findOne(Criteria criteria) {
        return list(criteria, PageRequest.of(1, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    protected final T requireOne(Criteria criteria, String message) {
        T existing = findOne(criteria);
        if (existing == null) {
            throw new PlatformException(message);
        }
        return existing;
    }

    protected final T selectIncludingDeleted(String id) {
        if (this instanceof SoftDeleteAbility<?> softDeleteAbility) {
            @SuppressWarnings("unchecked")
            SoftDeleteAbility<T> typed = (SoftDeleteAbility<T>) softDeleteAbility;
            return typed.selectIgnoreSoftDelete(id);
        }
        return select(id);
    }

    protected final void rejectDuplicate(T entity, Criteria criteria, String message) {
        rejectDuplicate(entity, criteria, () -> new PlatformException(message));
    }

    /**
     * Rejects a duplicate record while leaving the public business error
     * contract to the calling domain service.
     */
    protected final void rejectDuplicate(T entity, Criteria criteria,
                                         Supplier<? extends RuntimeException> exceptionSupplier) {
        if (existsOtherInCurrentScope(entity, criteria)) {
            throw Objects.requireNonNull(exceptionSupplier, "exceptionSupplier must not be null").get();
        }
    }

    protected final void rejectChanged(String fieldName, Object existingValue, Object incomingValue) {
        if (!Objects.equals(existingValue, incomingValue)) {
            throw new PlatformException(fieldName + " cannot be changed");
        }
    }

    protected final <V> void rejectChanged(T existing, T incoming, String fieldName, Function<T, V> valueReader) {
        if (existing != null) {
            rejectChanged(fieldName, valueReader.apply(existing), valueReader.apply(incoming));
        }
    }
}
