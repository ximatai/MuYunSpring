package net.ximatai.muyun.spring.platform.query;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authorized, database-paginated reads constrained by other entities' authorized rows.
 * Business services declare associations and required actions; SQL and scope composition stay here.
 * All participating services must use this host's database. Plans contain no resolved permissions;
 * every execution authorizes each participant again under the current identity and tenant.
 */
@Service
public class AuthorizedAssociationQuery {
    /** Retained rows are an explicit domain read policy, and never waive action or tenant checks. */
    public enum RelatedRecords { ACTIVE, INCLUDING_DELETED }

    private final IDatabaseOperations<?> database;
    private final EntityMetaResolver metadata;
    private final CriteriaSqlCompiler compiler;
    private final ActionExecutionPolicyService actions;
    private final DataScopeCriteriaService scopes;

    public AuthorizedAssociationQuery(IDatabaseOperations<?> database, EntityMetaResolver metadata,
                                      DatabaseValueConverter values, ActionExecutionPolicyService actions,
                                      DataScopeCriteriaService scopes) {
        this.database = Objects.requireNonNull(database);
        this.metadata = Objects.requireNonNull(metadata);
        this.compiler = new CriteriaSqlCompiler(Objects.requireNonNull(values));
        this.actions = Objects.requireNonNull(actions);
        this.scopes = Objects.requireNonNull(scopes);
    }

    public <T extends EntityContract> Query<T> from(CrudAbility<T> service, PlatformAction action) {
        return from(service, Objects.requireNonNull(action).executionPolicy());
    }

    public <T extends EntityContract> Query<T> from(CrudAbility<T> service, ActionExecutionPolicy action) {
        return new Query<>(Objects.requireNonNull(service), List.of(Objects.requireNonNull(action)),
                Criteria.of(), List.of());
    }

    /** Immutable declaration. Conditions and associations are always mandatory AND constraints. */
    public final class Query<T extends EntityContract> {
        private final CrudAbility<T> service;
        private final List<ActionExecutionPolicy> requiredActions;
        private final Criteria conditions;
        private final List<Association> associations;

        private Query(CrudAbility<T> service, List<ActionExecutionPolicy> requiredActions,
                      Criteria conditions, List<Association> associations) {
            this.service = service;
            this.requiredActions = List.copyOf(requiredActions);
            this.conditions = Criteria.copyOf(conditions);
            this.associations = List.copyOf(associations);
        }

        public Query<T> where(Criteria conditions) {
            return new Query<>(service, requiredActions, Criteria.copyOf(this.conditions)
                    .and(Objects.requireNonNull(conditions)), associations);
        }

        /** Requires another action and intersects its data range with all existing requirements. */
        public Query<T> requiring(ActionExecutionPolicy action) {
            var policies = new ArrayList<>(requiredActions);
            policies.add(Objects.requireNonNull(action));
            return new Query<>(service, policies, conditions, associations);
        }

        public Query<T> whereRelated(String field, Query<?> related) {
            return whereRelated(field, related, "id", RelatedRecords.ACTIVE);
        }

        /** Supports forward and reverse associations, including nested mandatory constraints. */
        public Query<T> whereRelated(String field, Query<?> related, String relatedField, RelatedRecords records) {
            Objects.requireNonNull(related);
            if (related.owner() != AuthorizedAssociationQuery.this) {
                throw new IllegalArgumentException("association queries must belong to the same host");
            }
            requireScalarField(table(service), field);
            requireScalarField(table(related.service), relatedField);
            validate(service, Criteria.of().isNotNull(field));
            validate(related.service, Criteria.of().isNotNull(relatedField));
            var relations = new ArrayList<>(associations);
            relations.add(new Association(field, related, relatedField, Objects.requireNonNull(records)));
            return new Query<>(service, requiredActions, conditions, relations);
        }

        public PageResult<T> page(PageRequest page, Sort... sorts) {
            Objects.requireNonNull(page);
            Resolved resolved = resolve(this, RelatedRecords.ACTIVE, 0);
            return inScope(service, resolved.crossTenant(), () -> service.pageQuery(resolved.criteria(), page, sorts));
        }

        public long count() {
            Resolved resolved = resolve(this, RelatedRecords.ACTIVE, 0);
            return inScope(service, resolved.crossTenant(), () -> service.count(resolved.criteria()));
        }

        private AuthorizedAssociationQuery owner() { return AuthorizedAssociationQuery.this; }
    }

    private record Association(String field, Query<?> related, String relatedField, RelatedRecords records) { }
    private record Resolved(Criteria criteria, boolean crossTenant) { }

    private Resolved resolve(Query<?> query, RelatedRecords records, int depth) {
        if (depth > 16) throw new IllegalArgumentException("association query nesting exceeds 16");
        var user = CurrentUserContext.currentUser();
        if (user.isEmpty()) throw new PlatformAccessDeniedException("association queries require an authenticated user");
        Criteria criteria = Criteria.copyOf(query.conditions);
        boolean crossTenant = true;
        for (var action : query.requiredActions) {
            actions.requireAuthorized(ActionExecutionContext.ofPolicy(query.service.getModuleAlias(), action, java.util.Set.of(), user));
            var scope = Objects.requireNonNull(query.service instanceof DataScopeAbility<?> scoped
                    ? scoped.readScopeByPolicy(action, Criteria.of())
                    : scopes.resolveReadScope(query.service.getModuleAlias(), action, Criteria.of(), user,
                            DataScopeFieldMapping.STANDARD), "authorization scope is unavailable");
            criteria.and(scope.criteria());
            crossTenant &= scope.crossTenant();
        }
        // Resolve each participant before entering the outer query's bypass scope.
        for (var association : query.associations) {
            Resolved related = resolve(association.related(), association.records(), depth + 1);
            var table = table(association.related().service);
            validate(association.related().service, related.criteria());
            var dialect = database.getDBInfo().getDatabaseType();
            var compiled = compiler.compile(related.criteria(), table, dialect);
            String schema = table.getSchema() == null ? database.getDefaultSchemaName() : table.getSchema();
            var sql = "SELECT " + SchemaBuildRules.quoteIdentifier(requireScalarField(table, association.relatedField()), dialect)
                    + " FROM " + SchemaBuildRules.qualifiedName(schema, table.getTableName(), dialect)
                    + (compiled.getSql().isBlank() ? "" : " WHERE " + compiled.getSql());
            criteria.inSubQuery(association.field(), SqlSubQuery.of(sql, compiled.getParams()));
        }
        Criteria scoped = inScope(query.service, crossTenant, () -> records == RelatedRecords.INCLUDING_DELETED
                ? query.service.tenantCriteria(criteria) : query.service.activeCriteria(criteria));
        validate(query.service, scoped);
        return new Resolved(scoped, crossTenant);
    }

    private <R> R inScope(CrudAbility<?> service, boolean crossTenant, Supplier<R> work) {
        if (crossTenant) {
            try (var ignored = TenantContext.bypassTenantFilter("authorized association query")) { return work.get(); }
        }
        // An ambient bypass is not authorization for this participant.
        String tenant = TenantContext.currentTenantId().orElse(null);
        if (tenant == null && !(service instanceof GlobalScopedAbility<?>)) {
            throw new PlatformAccessDeniedException("association query requires a tenant or explicit cross-tenant authorization");
        }
        try (var ignored = TenantContext.use(tenant)) { return work.get(); }
    }

    private TableMeta table(CrudAbility<?> service) {
        if (service.getDao() instanceof DynamicRecordDao dynamic) return dynamic.tableMeta();
        if (service.modelClass() == null) throw new IllegalArgumentException("query service has no entity metadata");
        return metadata.resolve(service.modelClass()).asTableMeta();
    }

    private void validate(CrudAbility<?> service, Criteria criteria) {
        if (service.getDao() instanceof DynamicRecordDao dynamic) dynamic.validateQueryable(criteria);
    }

    private String requireScalarField(TableMeta table, String field) {
        var metadata = table.findByFieldName(Objects.requireNonNull(field));
        if (metadata == null || metadata.getCollectionElementType().isPresent()) {
            throw new IllegalArgumentException("association requires a persistent scalar field: " + field);
        }
        return metadata.getColumnName();
    }
}
