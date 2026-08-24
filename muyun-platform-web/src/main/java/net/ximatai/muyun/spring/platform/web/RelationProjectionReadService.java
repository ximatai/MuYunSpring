package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class RelationProjectionReadService {
    private final Supplier<RelationProjectionQueryExecutor> projectionQueryExecutor;
    private final RelationProjectionDatabaseTypeProvider databaseTypeProvider;

    public RelationProjectionReadService() {
        this((RelationProjectionQueryExecutor) null, null);
    }

    @Autowired
    public RelationProjectionReadService(ObjectProvider<RelationProjectionQueryExecutor> projectionQueryExecutor,
                                         ObjectProvider<RelationProjectionDatabaseTypeProvider> databaseTypeProvider) {
        this(projectionQueryExecutor == null ? null : projectionQueryExecutor::getIfAvailable,
                databaseTypeProvider == null ? null : databaseTypeProvider.getIfAvailable());
    }

    RelationProjectionReadService(RelationProjectionQueryExecutor projectionQueryExecutor,
                                  RelationProjectionDatabaseTypeProvider databaseTypeProvider) {
        this((Supplier<RelationProjectionQueryExecutor>) () -> projectionQueryExecutor, databaseTypeProvider);
    }

    private RelationProjectionReadService(Supplier<RelationProjectionQueryExecutor> projectionQueryExecutor,
                                          RelationProjectionDatabaseTypeProvider databaseTypeProvider) {
        this.projectionQueryExecutor = projectionQueryExecutor == null ? () -> null : projectionQueryExecutor;
        this.databaseTypeProvider = databaseTypeProvider == null
                ? new RelationProjectionDatabaseTypeProvider()
                : databaseTypeProvider;
    }

    public boolean supportsListQuery(StaticModuleDefinition definition, RecordReadProjection projection) {
        return describeListQuery(definition, projection).supported();
    }

    public ProjectionQueryDescriptor describeListQuery(StaticModuleDefinition definition,
                                                       RecordReadProjection projection) {
        return describeListQuery(definition == null ? List.of() : List.of(definition), definition, projection);
    }

    public ProjectionQueryDescriptor describeListQuery(java.util.List<StaticModuleDefinition> definitions,
                                                       StaticModuleDefinition definition,
                                                       RecordReadProjection projection) {
        if (projectionQueryExecutor() == null) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.MISSING_EXECUTOR);
        }
        if (definition == null) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.MISSING_DEFINITION);
        }
        if (projection == null) {
            return ProjectionQueryDescriptor.unsupported(definition.moduleAlias(), null,
                    java.util.Set.of(), ProjectionQueryFallbackReason.MISSING_PROJECTION);
        }
        if (projection.postReadTransforms() != null && !projection.postReadTransforms().isEmpty()) {
            if (!RecordReadProjectionPostProcessor.supportsSqlOutput(projection)) {
                return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.POST_READ_TRANSFORM);
            }
            if (RecordReadProjectionPostProcessor.hasStorageProtectedOutput(definitions, definition, projection)) {
                return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.PROTECTED_FIELD);
            }
        }
        if (RecordReadProjectionPostProcessor.hasStorageProtectedOutput(definitions, definition, projection)) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.PROTECTED_FIELD);
        }
        if (!hasRelationProjectionCandidate(definition, projection)) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.NO_RELATION_OUTPUT);
        }
        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                definitions == null || definitions.isEmpty() ? List.of(definition) : definitions,
                definition,
                projection,
                databaseTypeProvider.databaseType(),
                java.util.Set.of()
        );
        if (!plan.hasRelationProjection()) {
            return ProjectionQueryDescriptor.unsupported(projection,
                    ProjectionQueryFallbackReason.PLAN_HAS_NO_RELATION_PROJECTION);
        }
        return ProjectionQueryDescriptor.supported(projection, plan);
    }

    private boolean hasRelationProjectionCandidate(StaticModuleDefinition definition, RecordReadProjection projection) {
        return (!definition.projectionJoins().isEmpty()
                || !definition.references().isEmpty()
                || hasReadProjectionOutput(definition, projection)
                || projection.outputFields().stream()
                .filter(field -> field.relationCode() != null)
                .anyMatch(field -> field.relationCode().contains(".")))
                && (hasReadProjectionOutput(definition, projection)
                || projection.outputFields().stream().anyMatch(field -> field.relationCode() != null));
    }

    private boolean hasReadProjectionOutput(StaticModuleDefinition definition, RecordReadProjection projection) {
        if (definition.readProjections().isEmpty()) {
            return false;
        }
        java.util.Set<String> outputFields = definition.readProjections().stream()
                .map(StaticModuleReadProjectionDefinition::outputField)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return projection.outputFields().stream()
                .filter(field -> field.relationCode() == null)
                .map(ViewFieldRef::fieldName)
                .anyMatch(outputFields::contains);
    }

    public Optional<PageResult<Map<String, Object>>> queryList(StaticModuleDefinition definition,
                                                              RecordReadProjection projection,
                                                              Criteria criteria,
                                                              PageRequest pageRequest,
                                                              Sort... sorts) {
        return queryList(List.of(definition), definition, projection, criteria, pageRequest, sorts);
    }

    public Optional<PageResult<Map<String, Object>>> queryList(java.util.List<StaticModuleDefinition> definitions,
                                                              StaticModuleDefinition definition,
                                                              RecordReadProjection projection,
                                                              Criteria criteria,
                                                              PageRequest pageRequest,
                                                              Sort... sorts) {
        return queryList(definitions, definition, projection, criteria, pageRequest, java.util.Set.of(), sorts);
    }

    public Optional<PageResult<Map<String, Object>>> queryListWithInternalFields(
            java.util.List<StaticModuleDefinition> definitions,
            StaticModuleDefinition definition,
            RecordReadProjection projection,
            Criteria criteria,
            PageRequest pageRequest,
            Sort... sorts) {
        return queryList(definitions, definition, projection, criteria, pageRequest,
                projection == null ? java.util.Set.of() : java.util.Set.copyOf(projection.internalReadFields()),
                sorts);
    }

    private Optional<PageResult<Map<String, Object>>> queryList(
            java.util.List<StaticModuleDefinition> definitions,
            StaticModuleDefinition definition,
            RecordReadProjection projection,
            Criteria criteria,
            PageRequest pageRequest,
            java.util.Set<String> additionalResponseFields,
            Sort... sorts) {
        RelationProjectionQueryExecutor executor = projectionQueryExecutor();
        ProjectionQueryDescriptor descriptor = describeListQuery(definitions, definition, projection);
        if (executor == null || !descriptor.supported()) {
            return Optional.empty();
        }
        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                definitions,
                definition,
                projection,
                databaseTypeProvider.databaseType(),
                requiredMainFields(criteria, sorts)
        );
        if (!plan.hasRelationProjection()) {
            return Optional.empty();
        }
        PageResult<Map<String, Object>> page = executor.page(plan, criteria, pageRequest, additionalResponseFields, sorts);
        List<Map<String, Object>> outputRecords = RecordReadProjectionPostProcessor.applySqlOutput(
                definitions,
                definition,
                projection,
                page.getRecords(),
                FieldOutputContext.LIST
        );
        return Optional.of(PageResult.of(outputRecords, page.getTotal(),
                PageRequest.of(page.getPageNum(), page.getPageSize())));
    }

    public Optional<List<Map<String, Object>>> aggregateList(java.util.List<StaticModuleDefinition> definitions,
                                                              StaticModuleDefinition definition,
                                                              RecordReadProjection projection,
                                                              Criteria criteria,
                                                              net.ximatai.muyun.database.core.orm.AggregateQuery query) {
        RelationProjectionQueryExecutor executor = projectionQueryExecutor();
        ProjectionQueryDescriptor descriptor = describeListQuery(definitions, definition, projection);
        if (executor == null || !descriptor.supported()) return Optional.empty();
        java.util.LinkedHashSet<String> requiredFields = new java.util.LinkedHashSet<>(requiredMainFields(criteria));
        requiredFields.addAll(query.groupByFields());
        query.selections().stream().map(net.ximatai.muyun.database.core.orm.AggregateSelection::field)
                .filter(java.util.Objects::nonNull).forEach(requiredFields::add);
        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(definitions, definition, projection,
                databaseTypeProvider.databaseType(), java.util.Set.copyOf(requiredFields));
        if (!plan.hasRelationProjection()) return Optional.empty();
        return Optional.of(executor.aggregate(plan, criteria, query));
    }

    private RelationProjectionQueryExecutor projectionQueryExecutor() {
        return projectionQueryExecutor.get();
    }

    private java.util.Set<String> requiredMainFields(Criteria criteria, Sort... sorts) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (criteria != null) {
            collectCriteriaFields(criteria.getRoot(), fields);
        }
        if (sorts != null) {
            java.util.Arrays.stream(sorts)
                    .filter(java.util.Objects::nonNull)
                    .map(Sort::getField)
                    .filter(field -> field != null && !field.isBlank())
                    .forEach(fields::add);
        }
        return java.util.Set.copyOf(fields);
    }

    private void collectCriteriaFields(CriteriaGroup group, java.util.Set<String> fields) {
        if (group == null) {
            return;
        }
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = entry.getNode();
            if (node instanceof CriteriaClause clause) {
                String field = clause.getField();
                if (field != null && !field.isBlank()) {
                    fields.add(field);
                }
            } else if (node instanceof CriteriaGroup childGroup) {
                collectCriteriaFields(childGroup, fields);
            }
        }
    }
}
