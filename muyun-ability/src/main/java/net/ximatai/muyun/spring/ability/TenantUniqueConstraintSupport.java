package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.constraint.StaticTenantUniqueConstraints;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class TenantUniqueConstraintSupport {
    private TenantUniqueConstraintSupport() {
    }

    static <T extends EntityContract, R> R persist(CrudAbility<T> ability, T entity, Supplier<R> write) {
        if (constraints(ability).isEmpty()) return write.get();
        try {
            return PlatformAbilityDispatcher.inStatementTransaction(write);
        } catch (RuntimeException failure) {
            // PostgreSQL rejects reads after a statement error until its savepoint is rolled back.
            throw translatePersistFailure(ability, entity, failure);
        }
    }

    static <T extends EntityContract> void validate(CrudAbility<T> ability, T entity) {
        if (ability == null || entity == null) {
            return;
        }
        constraints(ability).forEach(constraint -> validate(ability, entity, constraint));
    }

    static <T extends EntityContract> RuntimeException translatePersistFailure(CrudAbility<T> ability,
                                                                                T entity,
                                                                                RuntimeException failure) {
        if (!isDatabaseUniqueViolation(failure)) {
            return failure;
        }
        PlatformException translated = null;
        for (TenantUniqueConstraintDefinition constraint : constraints(ability)) {
            List<T> conflicts = conflictingRecords(ability, entity, constraint);
            if (conflicts.isEmpty()) continue;
            // More than one matching constraint is ambiguous; preserve the database failure.
            if (translated != null) return failure;
            translated = tenantUniqueConflict(ability, constraint, conflicts, failure);
        }
        return translated == null ? failure : translated;
    }

    private static <T extends EntityContract> void validate(CrudAbility<T> ability,
                                                             T entity,
                                                             TenantUniqueConstraintDefinition constraint) {
        List<T> conflicts = conflictingRecords(ability, entity, constraint);
        if (!conflicts.isEmpty()) {
            throw tenantUniqueConflict(ability, constraint, conflicts, null);
        }
    }

    private static <T extends EntityContract> List<T> conflictingRecords(CrudAbility<T> ability,
                                                                           T entity,
                                                                           TenantUniqueConstraintDefinition constraint) {
        Criteria criteria = Criteria.of().eqNullable(StandardEntitySchema.TENANT_ID_FIELD, entity.getTenantId());
        for (String field : constraint.fieldNames()) {
            Object value = value(ability, entity, field);
            if (value == null) {
                return List.of();
            }
            criteria.eq(field, value);
        }
        return ability.getDao().query(criteria, PageRequest.of(1, 2)).stream()
                .filter(existing -> !Objects.equals(existing.getId(), entity.getId()))
                .toList();
    }

    private static <T extends EntityContract> PlatformException tenantUniqueConflict(CrudAbility<T> ability,
                                                                                        TenantUniqueConstraintDefinition constraint,
                                                                                        List<T> conflicts,
                                                                                        Throwable cause) {
        T active = conflicts.stream()
                .filter(existing -> !Boolean.TRUE.equals(existing.getDeleted()))
                .findFirst()
                .orElse(null);
        if (active == null && !conflicts.isEmpty()) {
            return softDeletedConflict(ability, conflicts.getFirst(), cause);
        }
        return PlatformErrors.conflict(PlatformErrorCodes.CONFLICT_UNIQUE, constraint.violationMessage(), cause,
                ErrorScope.module(ability.getModuleAlias()),
                Map.of("moduleAlias", ability.getModuleAlias(), "fields", constraint.fieldNames()));
    }

    private static PlatformException softDeletedConflict(CrudAbility<?> ability,
                                                          EntityContract retained,
                                                          Throwable cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resourceModuleAlias", ability.getModuleAlias());
        details.put("resourceRecordId", retained.getId());
        if (retained.getDeletedAt() != null) {
            details.put("deletedAt", retained.getDeletedAt());
        }
        details.put("recoveryAvailable", Boolean.TRUE);
        String message = "tenant unique identity is retained by a soft-deleted record; restore it from the recycle bin before reusing it";
        return PlatformErrors.conflict(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT, message, cause,
                ErrorScope.module(ability.getModuleAlias()), details);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> List<TenantUniqueConstraintDefinition> constraints(CrudAbility<T> ability) {
        if (ability instanceof TenantUniqueConstraintProvider provider) {
            return provider.tenantUniqueConstraints();
        }
        return StaticTenantUniqueConstraints.resolve(ability.modelClass());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> Object value(CrudAbility<T> ability, T entity, String fieldName) {
        if (ability instanceof TenantUniqueConstraintProvider provider) {
            return provider.tenantUniqueConstraintValue(entity, fieldName);
        }
        Class<?> type = entity.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(entity);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("cannot read tenant unique constraint field: " + fieldName, exception);
            }
        }
        throw new IllegalArgumentException("unknown tenant unique constraint field: "
                + entity.getClass().getName() + "." + fieldName);
    }

    private static boolean isDatabaseUniqueViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
