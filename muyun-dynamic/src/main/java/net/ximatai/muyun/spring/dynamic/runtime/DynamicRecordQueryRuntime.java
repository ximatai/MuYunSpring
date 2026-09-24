package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Source-neutral execution kernel for the ordinary dynamic-record read surface.
 *
 * <p>Reads share metadata and permission scope with other collaborators through
 * {@link DynamicRecordAccessContext}; sibling orchestration never calls the facade.</p>
 */
final class DynamicRecordQueryRuntime {
    private final DynamicRecordAccessContext access;

    DynamicRecordQueryRuntime(DynamicRecordAccessContext access) {
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    DynamicRecord select(String moduleAlias, String entityAlias, String id) {
        Criteria base = Criteria.of().eq("id", id);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.VIEW, base);
        return access.withTenantScope(scope, () -> {
            if (!scope.restricted()) return access.entityService(moduleAlias, entityAlias).select(id);
            boolean visible = !access.entityService(moduleAlias, entityAlias)
                    .list(scope.criteria(), new PageRequest(0, 1)).isEmpty();
            return visible ? access.entityService(moduleAlias, entityAlias).select(id) : null;
        });
    }

    List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria,
                             PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).list(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).list(scoped, sorts));
    }

    List<DynamicRecord> listForAction(String moduleAlias, String entityAlias, PlatformAction action,
                                      Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, action.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).list(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> listForAction(String moduleAlias, String entityAlias, PlatformAction action,
                                      Criteria criteria, Sort... sorts) {
        return withScope(moduleAlias, action.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).list(scoped, sorts));
    }

    PageResult<DynamicRecord> page(String moduleAlias, String entityAlias, Criteria criteria,
                                   PageRequest pageRequest, Sort... sorts) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).pageQuery(scoped, pageRequest, sorts));
    }

    PageResult<DynamicRecord> pageForAction(String moduleAlias, String entityAlias, String actionCode,
                                            Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        DynamicActionDescriptor action = access.entityActionDescriptor(moduleAlias, entityAlias, actionCode);
        ActionExecutionPolicy policy = access.actionPolicy(action);
        access.authorize(moduleAlias, policy, java.util.Set.of());
        return withScope(moduleAlias, policy, criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).pageQuery(scoped, pageRequest, sorts));
    }

    List<DynamicRecord> children(String moduleAlias, String entityAlias, String parentId) {
        return withScope(moduleAlias, PlatformAction.TREE.executionPolicy(), Criteria.of(), (scope, scoped) -> {
            if (!scope.restricted()) {
                return access.entityService(moduleAlias, entityAlias).children(parentId);
            }
            return access.entityService(moduleAlias, entityAlias).children(scoped, parentId);
        });
    }

    List<DynamicRecord> childrenForAction(String moduleAlias, String entityAlias, String actionCode,
                                          Criteria criteria, String parentId) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DynamicActionDescriptor action = access.entityActionDescriptor(moduleAlias, entityAlias, actionCode);
        ActionExecutionPolicy policy = access.actionPolicy(action);
        access.authorize(moduleAlias, policy, java.util.Set.of());
        return withScope(moduleAlias, policy, criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).children(scoped, parentId));
    }

    long count(String moduleAlias, String entityAlias, Criteria criteria) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).count(scoped));
    }

    List<Map<String, Object>> aggregate(String moduleAlias, String entityAlias, Criteria criteria,
                                        AggregateQuery query) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).aggregate(scoped, query));
    }

    List<DynamicRecord> sortedList(String moduleAlias, String entityAlias, Criteria criteria) {
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> access.entityService(moduleAlias, entityAlias).sortedList(scoped));
    }

    <R> R withQueryReadScope(String moduleAlias, Criteria criteria, Function<Criteria, R> action) {
        Objects.requireNonNull(action, "action must not be null");
        // Projection readers execute SQL outside the entity DAO, so carry its active-row
        // boundary into the callback while the resolved tenant scope is still installed.
        return withScope(moduleAlias, PlatformAction.QUERY.executionPolicy(), criteria,
                scoped -> action.apply(access.entityService(moduleAlias, access.mainEntityAlias(moduleAlias))
                        .activeCriteria(scoped)));
    }

    private <R> R withScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria,
                            Function<Criteria, R> action) {
        return withScope(moduleAlias, policy, criteria, (ignored, scoped) -> action.apply(scoped));
    }

    private <R> R withScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria,
                            java.util.function.BiFunction<DataScopeCriteriaResult, Criteria, R> action) {
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, policy, criteria);
        return access.withTenantScope(scope, () -> action.apply(scope, scope.criteria()));
    }

}
