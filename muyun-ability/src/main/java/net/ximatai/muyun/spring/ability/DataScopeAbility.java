package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public interface DataScopeAbility<T extends EntityContract> extends CrudAbility<T> {
    DataScopeCriteriaService getDataScopeCriteriaService();

    default DataScopeCriteriaResult readScope(PlatformAction action, Criteria criteria) {
        return getDataScopeCriteriaService().resolveReadScope(
                getModuleAlias(),
                action.executionPolicy(),
                criteria == null ? Criteria.of() : criteria,
                CurrentUserContext.currentUser(),
                dataScopeFieldMapping()
        );
    }

    default PageResult<T> pageQueryForAction(PlatformAction action,
                                             Criteria criteria,
                                             PageRequest pageRequest,
                                             Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(action, criteria);
        return withDataScopeTenant(scope, () -> pageQuery(scope.criteria(), pageRequest, sorts));
    }

    default List<T> listForAction(PlatformAction action,
                                  Criteria criteria,
                                  PageRequest pageRequest,
                                  Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(action, criteria);
        return withDataScopeTenant(scope, () -> list(scope.criteria(), pageRequest, sorts));
    }

    default List<T> listForAction(PlatformAction action,
                                  Criteria criteria,
                                  Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(action, criteria);
        return withDataScopeTenant(scope, () -> list(scope.criteria(), sorts));
    }

    default long countForAction(PlatformAction action, Criteria criteria) {
        DataScopeCriteriaResult scope = readScope(action, criteria);
        return withDataScopeTenant(scope, () -> count(scope.criteria()));
    }

    default T selectForAction(PlatformAction action, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        DataScopeCriteriaResult scoped = readScope(action, Criteria.of().eq(StandardEntitySchema.ID_FIELD, id));
        return withDataScopeTenant(scoped, () -> {
            if (count(scoped.criteria()) == 0) {
                return null;
            }
            return select(id);
        });
    }

    default void requireRecordScope(PlatformAction action, Collection<String> ids) {
        requireRecordScopeResult(action.executionPolicy(), ids);
    }

    default void requireRecordScope(ActionExecutionPolicy policy, Collection<String> ids) {
        requireRecordScopeResult(policy, ids);
    }

    default DataScopeCriteriaResult requireRecordScopeResult(ActionExecutionPolicy policy, Collection<String> ids) {
        java.util.Objects.requireNonNull(policy, "policy must not be null");
        if (!policy.requiresDataScope()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("record scope requires record ids: " + getModuleAlias());
        }
        Criteria criteria = normalized.size() == 1
                ? Criteria.of().eq(StandardEntitySchema.ID_FIELD, normalized.iterator().next())
                : Criteria.of().in(StandardEntitySchema.ID_FIELD, List.copyOf(normalized));
        DataScopeCriteriaResult scope = readScopeByPolicy(policy, criteria);
        long visible = withDataScopeTenant(scope, () -> count(scope.criteria()));
        if (visible != normalized.size()) {
            throw new PlatformAccessDeniedException(
                    "record data permission denied: " + getModuleAlias() + "." + policy.actionCode(),
                    ErrorScope.module(getModuleAlias()).action(policy.actionCode()));
        }
        return scope;
    }

    default DataScopeCriteriaResult readScopeByPolicy(ActionExecutionPolicy policy, Criteria criteria) {
        return readScopeByPolicy(getModuleAlias(), policy, criteria);
    }

    /** Resolves data scope for an action governed by a source aggregate rather than this child service alias. */
    default DataScopeCriteriaResult readScopeByPolicy(String moduleAlias,
                                                      ActionExecutionPolicy policy,
                                                      Criteria criteria) {
        java.util.Objects.requireNonNull(policy, "policy must not be null");
        return getDataScopeCriteriaService().resolveReadScope(
                java.util.Objects.requireNonNull(moduleAlias, "moduleAlias must not be null"),
                policy,
                criteria == null ? Criteria.of() : criteria,
                CurrentUserContext.currentUser(),
                dataScopeFieldMapping()
        );
    }

    default DataScopeFieldMapping dataScopeFieldMapping() {
        if (this instanceof DataScopeFieldMappingAbility mappingAbility) {
            DataScopeFieldMapping mapping = mappingAbility.dataScopeFieldMapping();
            return mapping == null ? DataScopeFieldMapping.STANDARD : mapping;
        }
        return DataScopeFieldMapping.STANDARD;
    }

    default List<T> sortedListForAction(PlatformAction action, Criteria criteria) {
        return listForAction(action, criteria, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    default <R> R withDataScopeTenant(DataScopeCriteriaResult scope, Supplier<R> supplier) {
        if (scope.crossTenant()) {
            try (net.ximatai.muyun.spring.common.tenant.TenantContext.Scope ignored =
                         net.ximatai.muyun.spring.common.tenant.TenantContext.bypassTenantFilter(
                                 "data scope allows cross-tenant read")) {
                return supplier.get();
            }
        }
        return supplier.get();
    }

    default List<T> childrenForAction(PlatformAction action, String parentId) {
        if (!(this instanceof TreeAbility<?>)) {
            throw new IllegalStateException("childrenForAction requires TreeAbility: " + getModuleAlias());
        }
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        if (!TreeAbility.ROOT_ID.equals(parentId) && selectForAction(action, parentId) == null) {
            return List.of();
        }
        Criteria criteria = Criteria.of().eq(PlatformAbilityFields.TREE_PARENT_FIELD, parentId);
        return sortedListForAction(action, criteria);
    }

    @SuppressWarnings("unchecked")
    static <T extends EntityContract> DataScopeAbility<T> cast(CrudAbility<?> ability) {
        return (DataScopeAbility<T>) ability;
    }

    private Set<String> normalizeRecordIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .forEach(normalized::add);
        return java.util.Collections.unmodifiableSet(normalized);
    }
}
