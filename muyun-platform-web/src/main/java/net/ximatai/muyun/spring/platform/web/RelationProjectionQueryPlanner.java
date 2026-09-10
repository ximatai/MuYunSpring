package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class RelationProjectionQueryPlanner {
    private RelationProjectionQueryPlanner() {
    }

    public static RelationProjectionSqlPlan plan(StaticModuleDefinition definition,
                                                 RecordReadProjection projection,
                                                 DBInfo.Type databaseType,
                                                 java.util.Set<String> requiredMainFields) {
        return plan(List.of(definition), definition, projection, databaseType, requiredMainFields,
                RelationProjectionPlanningOptions.defaults());
    }

    public static RelationProjectionSqlPlan plan(List<StaticModuleDefinition> definitions,
                                                 StaticModuleDefinition definition,
                                                 RecordReadProjection projection,
                                                 DBInfo.Type databaseType,
                                                 java.util.Set<String> requiredMainFields) {
        return plan(definitions, definition, projection, databaseType, requiredMainFields,
                RelationProjectionPlanningOptions.defaults());
    }

    public static RelationProjectionSqlPlan plan(List<StaticModuleDefinition> definitions,
                                                 StaticModuleDefinition definition,
                                                 RecordReadProjection projection,
                                                 DBInfo.Type databaseType,
                                                 java.util.Set<String> requiredMainFields,
                                                 RelationProjectionPlanningOptions options) {
        if (definition == null) {
            throw new IllegalArgumentException("static module definition must not be null");
        }
        if (projection == null) {
            throw new IllegalArgumentException("record read projection must not be null");
        }
        if (!definition.moduleAlias().equals(projection.moduleAlias())) {
            throw new IllegalArgumentException("projection module alias mismatch: "
                    + definition.moduleAlias() + " != " + projection.moduleAlias());
        }
        DBInfo.Type dbType = databaseType == null ? DBInfo.Type.POSTGRESQL : databaseType;
        RelationProjectionPlanningOptions planningOptions = options == null
                ? RelationProjectionPlanningOptions.defaults()
                : options;
        if (definition.entities().isEmpty()) {
            return emptyPlan(definition, RecordReadProjectionGraphAdapter.adapt(projection), dbType);
        }
        java.util.Set<String> requiredFields = requiredMainFields == null ? java.util.Set.of()
                : java.util.Set.copyOf(requiredMainFields);
        ProjectionGraph projectionGraph = RecordReadProjectionGraphPlanner.plan(
                definitions == null || definitions.isEmpty() ? List.of(definition) : definitions,
                definition,
                projection,
                planningOptions
        );

        EntityDefinition mainEntity = definition.entities().getFirst();
        LinkedHashSet<ViewFieldRef> relationFields = projection.outputFields().stream()
                .filter(field -> field.relationCode() != null)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<ViewFieldRef> readProjectionFields = projection.outputFields().stream()
                .filter(field -> field.relationCode() == null)
                .filter(field -> readProjection(definition, field.fieldName()) != null)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (relationFields.isEmpty() && readProjectionFields.isEmpty()) {
            return emptyPlan(definition, projectionGraph, dbType);
        }
        LinkedHashSet<ViewFieldRef> referenceFields = new LinkedHashSet<>(relationFields);
        referenceFields.addAll(readProjectionFields);
        RelationProjectionSqlPlan referencePlan = referencePlan(
                definitions == null || definitions.isEmpty() ? List.of(definition) : definitions,
                definition,
                projection,
                referenceFields,
                dbType,
                requiredFields,
                planningOptions,
                projectionGraph
        );
        if (referencePlan != null) {
            return referencePlan;
        }
        if (definition.projectionJoins().isEmpty()) {
            return emptyPlan(definition, projectionGraph, dbType);
        }

        Map<String, EntityDefinition> entities = entitiesByAlias(definition.entities());
        Map<String, RelationProjectionJoinDefinition> joins = joinsByRelation(definition.projectionJoins());

        Map<String, FieldDefinition> mainFields = fieldsByName(mainEntity);
        LinkedHashMap<String, SelectField> selectFields = new LinkedHashMap<>();
        addMainProjectionFields(selectFields, mainFields, projection, requiredFields);
        addStandardMainFields(selectFields);
        LinkedHashSet<String> queryableFields = new LinkedHashSet<>(selectFields.keySet());
        LinkedHashSet<String> sortableFields = new LinkedHashSet<>(queryableFields);

        LinkedHashSet<String> responseFields = new LinkedHashSet<>();
        responseFields.add(StandardEntitySchema.ID_FIELD);
        responseFields.add(StandardEntitySchema.VERSION_FIELD);
        responseFields.add(StandardEntitySchema.DELETED_AT_FIELD);
        projection.outputFields().stream()
                .map(ViewFieldRef::fieldName)
                .forEach(responseFields::add);
        LinkedHashSet<String> requiredRelations = new LinkedHashSet<>();
        for (ViewFieldRef field : relationFields) {
            EntityDefinition target = entities.get(field.relationCode());
            RelationProjectionJoinDefinition join = joins.get(field.relationCode());
            if (target == null || join == null) {
                throw new IllegalArgumentException("projection relation is not declared: "
                        + definition.moduleAlias() + "." + field.relationCode());
            }
            if (!join.cardinality().safeForPageJoin()) {
                throw new IllegalArgumentException("projection relation cardinality is not safe for page join: "
                        + definition.moduleAlias() + "." + field.relationCode() + "." + join.cardinality());
            }
            FieldDefinition targetField = target.fields().stream()
                    .filter(item -> item.fieldName().equals(field.fieldName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("projection relation field is not declared: "
                            + definition.moduleAlias() + "." + field.relationCode() + "." + field.fieldName()));
            addSelectField(selectFields, new SelectField(
                    field.relationCode(),
                    targetField.columnName(),
                    targetField.fieldName()
            ));
            requiredRelations.add(field.relationCode());
            validateJoinCount(requiredRelations.size(), planningOptions.maxJoinCount(), definition.moduleAlias());
        }

        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();
        sql.append("select ");
        sql.append(selectFields.values().stream()
                .map(field -> selectExpression(field, dbType)
                        + " as " + quote(field.outputName(), dbType))
                .collect(java.util.stream.Collectors.joining(", ")));
        sql.append(" from ")
                .append(qualifiedTable(mainEntity, dbType))
                .append(" ")
                .append(quote(RelationProjectionSqlNames.MAIN_ALIAS, dbType));
        for (String relationCode : requiredRelations) {
            appendJoin(sql, params, joins.get(relationCode), dbType);
        }
        return new RelationProjectionSqlPlan(
                sql.toString(),
                params,
                queryableFields,
                sortableFields,
                responseFields,
                List.copyOf(relationFields),
                dbType,
                projectionGraph
        );
    }

    public static RelationProjectionSqlPlan plan(StaticModuleDefinition definition,
                                                 RecordReadProjection projection,
                                                 DBInfo.Type databaseType) {
        return plan(definition, projection, databaseType, java.util.Set.of());
    }

    /**
     * Plans a physical main-table source for aggregate reads when a standard module has no
     * relation projection. The caller is still responsible for applying the list action's
     * query, data-range, tenant and visibility criteria before executing this plan.
     */
    public static RelationProjectionSqlPlan mainTableAggregatePlan(StaticModuleDefinition definition,
                                                                    DBInfo.Type databaseType,
                                                                    java.util.Set<String> requiredMainFields) {
        if (definition == null || definition.entities().isEmpty()) {
            throw new IllegalArgumentException("static aggregate requires a main entity definition");
        }
        DBInfo.Type dbType = databaseType == null ? DBInfo.Type.POSTGRESQL : databaseType;
        EntityDefinition mainEntity = definition.entities().getFirst();
        LinkedHashMap<String, SelectField> selectFields = new LinkedHashMap<>();
        Map<String, FieldDefinition> fieldsByName = fieldsByName(mainEntity);
        for (String fieldName : requiredMainFields == null ? java.util.Set.<String>of() : requiredMainFields) {
            FieldDefinition field = fieldsByName.get(fieldName);
            if (field != null) {
                addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                        field.columnName(), field.fieldName()));
            }
        }
        addStandardMainFields(selectFields);
        String sql = "select " + selectFields.values().stream()
                .map(field -> selectExpression(field, dbType) + " as " + quote(field.outputName(), dbType))
                .collect(java.util.stream.Collectors.joining(", "))
                + " from " + qualifiedTable(mainEntity, dbType) + " "
                + quote(RelationProjectionSqlNames.MAIN_ALIAS, dbType);
        java.util.Set<String> fields = java.util.Set.copyOf(selectFields.keySet());
        return new RelationProjectionSqlPlan(sql, Map.of(), fields, fields, java.util.Set.of(), List.of(), dbType,
                null);
    }

    private static RelationProjectionSqlPlan referencePlan(List<StaticModuleDefinition> definitions,
                                                           StaticModuleDefinition definition,
                                                           RecordReadProjection projection,
                                                           LinkedHashSet<ViewFieldRef> relationFields,
                                                           DBInfo.Type dbType,
                                                           java.util.Set<String> requiredFields,
                                                           RelationProjectionPlanningOptions options,
                                                           ProjectionGraph projectionGraph) {
        Map<String, StaticModuleDefinition> modules =
                RecordReadProjectionReferenceResolver.modulesByAlias(definitions, definition);
        Map<String, FieldDefinition> mainFields = fieldsByName(definition.entities().getFirst());
        LinkedHashMap<String, SelectField> selectFields = new LinkedHashMap<>();
        addMainProjectionFields(selectFields, mainFields, projection, requiredFields);
        addStandardMainFields(selectFields);
        LinkedHashSet<String> queryableFields = new LinkedHashSet<>(selectFields.keySet());
        LinkedHashSet<String> sortableFields = new LinkedHashSet<>(queryableFields);

        LinkedHashSet<String> responseFields = new LinkedHashSet<>();
        responseFields.add(StandardEntitySchema.ID_FIELD);
        responseFields.add(StandardEntitySchema.VERSION_FIELD);
        responseFields.add(StandardEntitySchema.DELETED_AT_FIELD);
        projection.outputFields().stream()
                .map(ViewFieldRef::fieldName)
                .forEach(responseFields::add);

        Map<String, ProjectionGraphNode> nodes = graphNodes(projectionGraph);
        Map<String, ProjectionGraphEdge> outputEdges = referenceOutputEdges(projectionGraph);
        List<ProjectionGraphEdge> selectedOutputEdges = new ArrayList<>();
        for (ViewFieldRef field : relationFields) {
            ProjectionGraphEdge output = outputEdges.get(outputNodeId(field));
            if (output == null) {
                return null;
            }
            selectedOutputEdges.add(output);
            ProjectionGraphNode source = nodes.get(output.sourceNodeId());
            EntityDefinition targetEntity = graphNodeEntity(modules, source);
            String targetColumn = columnName(targetEntity, output.targetFieldName());
            if (output.existsProjection()) {
                addSelectField(selectFields, SelectField.expression(
                        qualified(source.tableAlias(), targetColumn, dbType) + " is not null",
                        field.fieldName()
                ));
            } else {
                addSelectField(selectFields, new SelectField(
                        source.tableAlias(),
                        targetColumn,
                        field.fieldName()
                ));
            }
            StaticModuleReadProjectionDefinition readProjection = field.relationCode() == null
                    ? readProjection(definition, field.fieldName())
                    : null;
            if (readProjection != null) {
                if (readProjection.filterable()) {
                    queryableFields.add(field.fieldName());
                }
                if (readProjection.sortable()) {
                    sortableFields.add(field.fieldName());
                }
            }
        }
        LinkedHashMap<String, GraphJoin> joins = referenceJoinEdges(
                projectionGraph,
                nodes,
                modules,
                selectedOutputEdges,
                options,
                definition.moduleAlias()
        );
        if (joins.isEmpty()) {
            return null;
        }

        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();
        sql.append("select ");
        sql.append(selectFields.values().stream()
                .map(field -> selectExpression(field, dbType)
                        + " as " + quote(field.outputName(), dbType))
                .collect(java.util.stream.Collectors.joining(", ")));
        sql.append(" from ")
                .append(qualifiedTable(definition.entities().getFirst(), dbType))
                .append(" ")
                .append(quote(RelationProjectionSqlNames.MAIN_ALIAS, dbType));
        for (GraphJoin join : joins.values()) {
            appendReferenceJoin(sql, params, join, dbType);
        }
        return new RelationProjectionSqlPlan(
                sql.toString(),
                params,
                queryableFields,
                sortableFields,
                responseFields,
                List.copyOf(relationFields),
                dbType,
                projectionGraph
        );
    }

    private static LinkedHashMap<String, GraphJoin> referenceJoinEdges(ProjectionGraph graph,
                                                                       Map<String, ProjectionGraphNode> nodes,
                                                                       Map<String, StaticModuleDefinition> modules,
                                                                       List<ProjectionGraphEdge> outputEdges,
                                                                       RelationProjectionPlanningOptions options,
                                                                       String moduleAlias) {
        LinkedHashMap<String, GraphJoin> joins = new LinkedHashMap<>();
        if (graph == null || outputEdges == null || outputEdges.isEmpty()) {
            return joins;
        }
        java.util.Set<String> requiredJoinNodeIds = requiredJoinNodeIds(graph, outputEdges);
        for (ProjectionGraphEdge edge : graph.edges()) {
            if (edge.edgeKind() != ProjectionGraphEdgeKind.REFERENCE_JOIN) {
                continue;
            }
            if (!requiredJoinNodeIds.contains(edge.targetNodeId())) {
                continue;
            }
            ProjectionGraphNode target = nodes.get(edge.targetNodeId());
            joins.putIfAbsent(edge.targetNodeId(), new GraphJoin(edge, graphNodeEntity(modules, target)));
            validateJoinCount(joins.size(), options.maxJoinCount(), moduleAlias);
        }
        return joins;
    }

    private static java.util.Set<String> requiredJoinNodeIds(ProjectionGraph graph,
                                                             List<ProjectionGraphEdge> outputEdges) {
        Map<String, ProjectionGraphEdge> joinByTarget = referenceJoinEdgesByTarget(graph);
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (ProjectionGraphEdge output : outputEdges) {
            String currentNodeId = output.sourceNodeId();
            while (currentNodeId != null) {
                ProjectionGraphEdge join = joinByTarget.get(currentNodeId);
                if (join == null) {
                    break;
                }
                required.add(join.targetNodeId());
                currentNodeId = join.sourceNodeId();
            }
        }
        return java.util.Collections.unmodifiableSet(required);
    }

    private static Map<String, ProjectionGraphEdge> referenceJoinEdgesByTarget(ProjectionGraph graph) {
        LinkedHashMap<String, ProjectionGraphEdge> joins = new LinkedHashMap<>();
        if (graph != null) {
            for (ProjectionGraphEdge edge : graph.edges()) {
                if (edge.edgeKind() == ProjectionGraphEdgeKind.REFERENCE_JOIN) {
                    joins.putIfAbsent(edge.targetNodeId(), edge);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(joins);
    }

    private static Map<String, ProjectionGraphNode> graphNodes(ProjectionGraph graph) {
        LinkedHashMap<String, ProjectionGraphNode> nodes = new LinkedHashMap<>();
        if (graph != null) {
            for (ProjectionGraphNode node : graph.nodes()) {
                nodes.putIfAbsent(node.nodeId(), node);
            }
        }
        return java.util.Collections.unmodifiableMap(nodes);
    }

    private static Map<String, ProjectionGraphEdge> referenceOutputEdges(ProjectionGraph graph) {
        LinkedHashMap<String, ProjectionGraphEdge> outputEdges = new LinkedHashMap<>();
        if (graph != null) {
            for (ProjectionGraphEdge edge : graph.edges()) {
                if (edge.edgeKind() == ProjectionGraphEdgeKind.REFERENCE_OUTPUT_FIELD) {
                    outputEdges.putIfAbsent(edge.targetNodeId(), edge);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(outputEdges);
    }

    private static EntityDefinition graphNodeEntity(Map<String, StaticModuleDefinition> modules,
                                                    ProjectionGraphNode node) {
        if (node == null || node.moduleAlias() == null || node.entityAlias() == null) {
            throw new IllegalArgumentException("projection graph reference output source node is invalid: "
                    + (node == null ? null : node.nodeId()));
        }
        StaticModuleDefinition definition = modules.get(node.moduleAlias());
        if (definition == null) {
            throw new IllegalArgumentException("projection graph node module is not declared: " + node.moduleAlias());
        }
        return definition.entities().stream()
                .filter(entity -> node.entityAlias().equals(entity.alias()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("projection graph node entity is not declared: "
                        + node.moduleAlias() + "." + node.entityAlias()));
    }

    private static String outputNodeId(ViewFieldRef field) {
        return field.relationCode() == null
                ? "main:" + field.fieldName()
                : "relation:" + field.relationCode() + ":" + field.fieldName();
    }

    private static void validateJoinCount(int joinCount, int maxJoinCount, String moduleAlias) {
        if (joinCount > maxJoinCount) {
            throw new IllegalArgumentException("relation projection join count exceeds limit: "
                    + moduleAlias + "." + joinCount + " > " + maxJoinCount);
        }
    }

    private static void appendReferenceJoin(StringBuilder sql,
                                            Map<String, Object> params,
                                            GraphJoin join,
                                            DBInfo.Type databaseType) {
        sql.append(" left join ")
                .append(qualifiedTable(join.entity(), databaseType))
                .append(" ")
                .append(quote(join.edge().tableAlias(), databaseType))
                .append(" on ");
        List<String> predicates = new ArrayList<>();
        for (RelationProjectionJoinCondition condition : join.edge().joinConditions()) {
            predicates.add(qualified(condition.leftAlias(), condition.leftColumn(), databaseType)
                    + " = "
                    + qualified(condition.rightAlias(), condition.rightColumn(), databaseType));
        }
        String paramName = "__join_" + join.edge().tableAlias() + "_deleted";
        predicates.add(qualified(join.edge().tableAlias(), StandardEntitySchema.DELETED_COLUMN, databaseType)
                + " = :" + paramName);
        params.put(paramName, Boolean.FALSE);
        sql.append(String.join(" and ", predicates));
    }

    private record GraphJoin(ProjectionGraphEdge edge, EntityDefinition entity) {
    }

    private static RelationProjectionSqlPlan emptyPlan(StaticModuleDefinition definition,
                                                       ProjectionGraph projectionGraph,
                                                       DBInfo.Type databaseType) {
        return new RelationProjectionSqlPlan("select * from " + qualifiedTable(definition.entities().getFirst(), databaseType),
                Map.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), List.of(), databaseType,
                projectionGraph);
    }

    private static void addMainProjectionFields(LinkedHashMap<String, SelectField> selectFields,
                                                Map<String, FieldDefinition> mainFields,
                                                RecordReadProjection projection,
                                                java.util.Set<String> requiredMainFields) {
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>(projection.internalReadFields());
        projection.outputFields().stream()
                .filter(field -> field.relationCode() == null)
                .map(ViewFieldRef::fieldName)
                .forEach(fieldNames::add);
        fieldNames.addAll(requiredMainFields);
        for (String fieldName : fieldNames) {
            FieldDefinition field = mainFields.get(fieldName);
            if (field == null) {
                continue;
            }
            addSelectField(selectFields, new SelectField(
                    RelationProjectionSqlNames.MAIN_ALIAS,
                    field.columnName(),
                    field.fieldName()
            ));
        }
    }

    private static void addStandardMainFields(LinkedHashMap<String, SelectField> selectFields) {
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.ID_COLUMN, StandardEntitySchema.ID_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.TENANT_ID_COLUMN, StandardEntitySchema.TENANT_ID_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.VERSION_COLUMN, StandardEntitySchema.VERSION_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.DELETED_COLUMN, StandardEntitySchema.DELETED_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.DELETED_AT_COLUMN, StandardEntitySchema.DELETED_AT_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.DELETED_BY_COLUMN, StandardEntitySchema.DELETED_BY_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.CREATED_BY_COLUMN, StandardEntitySchema.CREATED_BY_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.CREATED_AT_COLUMN, StandardEntitySchema.CREATED_AT_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.UPDATED_BY_COLUMN, StandardEntitySchema.UPDATED_BY_FIELD));
        addSelectField(selectFields, new SelectField(RelationProjectionSqlNames.MAIN_ALIAS,
                StandardEntitySchema.UPDATED_AT_COLUMN, StandardEntitySchema.UPDATED_AT_FIELD));
    }

    private static void addSelectField(LinkedHashMap<String, SelectField> selectFields, SelectField field) {
        PlatformNameRules.requireFieldName(field.outputName(), "projectionOutputField");
        if (field.expression() == null) {
            RelationProjectionSqlNames.requireColumn(field.columnName(), "columnName");
        }
        SelectField existing = selectFields.putIfAbsent(field.outputName(), field);
        if (existing != null && !existing.sameSource(field)) {
            throw new IllegalArgumentException("projection output field conflicts: " + field.outputName());
        }
    }

    private static void appendJoin(StringBuilder sql,
                                   Map<String, Object> params,
                                   RelationProjectionJoinDefinition join,
                                   DBInfo.Type databaseType) {
        for (RelationProjectionJoinStep step : join.steps()) {
            int filterIndex = 0;
            sql.append(" left join ")
                    .append(qualifiedTable(step.schemaName(), step.tableName(), databaseType))
                    .append(" ")
                    .append(quote(step.tableAlias(), databaseType))
                    .append(" on ");
            List<String> predicates = new ArrayList<>();
            for (RelationProjectionJoinCondition condition : step.conditions()) {
                predicates.add(qualified(condition.leftAlias(), condition.leftColumn(), databaseType)
                        + " = "
                        + qualified(condition.rightAlias(), condition.rightColumn(), databaseType));
            }
            for (RelationProjectionJoinFilter filter : step.filters()) {
                String paramName = "__join_" + join.relationCode() + "_" + step.tableAlias() + "_" + filterIndex++;
                predicates.add(qualified(filter.tableAlias(), filter.columnName(), databaseType) + " = :" + paramName);
                params.put(paramName, filter.value());
            }
            sql.append(String.join(" and ", predicates));
        }
    }

    private static Map<String, EntityDefinition> entitiesByAlias(List<EntityDefinition> entities) {
        LinkedHashMap<String, EntityDefinition> byAlias = new LinkedHashMap<>();
        for (EntityDefinition entity : entities) {
            byAlias.putIfAbsent(entity.alias(), entity);
        }
        return java.util.Collections.unmodifiableMap(byAlias);
    }

    private static Map<String, FieldDefinition> fieldsByName(EntityDefinition entity) {
        LinkedHashMap<String, FieldDefinition> byName = new LinkedHashMap<>();
        for (FieldDefinition field : entity.fields()) {
            byName.putIfAbsent(field.fieldName(), field);
        }
        return java.util.Collections.unmodifiableMap(byName);
    }

    private static Map<String, RelationProjectionJoinDefinition> joinsByRelation(List<RelationProjectionJoinDefinition> joins) {
        LinkedHashMap<String, RelationProjectionJoinDefinition> byRelation = new LinkedHashMap<>();
        for (RelationProjectionJoinDefinition join : joins) {
            if (byRelation.putIfAbsent(join.relationCode(), join) != null) {
                throw new IllegalArgumentException("duplicate projection relation: " + join.relationCode());
            }
        }
        return java.util.Collections.unmodifiableMap(byRelation);
    }

    private static Map<String, StaticModuleDefinition> modulesByAlias(List<StaticModuleDefinition> definitions) {
        LinkedHashMap<String, StaticModuleDefinition> byAlias = new LinkedHashMap<>();
        for (StaticModuleDefinition definition : definitions) {
            if (definition != null) {
                byAlias.putIfAbsent(definition.moduleAlias(), definition);
            }
        }
        return java.util.Collections.unmodifiableMap(byAlias);
    }

    private static StaticModuleReadProjectionDefinition readProjection(StaticModuleDefinition definition,
                                                                       String outputField) {
        if (definition == null || outputField == null || outputField.isBlank()) {
            return null;
        }
        return definition.readProjections().stream()
                .filter(projection -> projection.outputField().equals(outputField))
                .findFirst()
                .orElse(null);
    }

    static String columnName(EntityDefinition entity, String fieldName) {
        return switch (fieldName) {
            case StandardEntitySchema.ID_FIELD -> StandardEntitySchema.ID_COLUMN;
            case StandardEntitySchema.TENANT_ID_FIELD -> StandardEntitySchema.TENANT_ID_COLUMN;
            case StandardEntitySchema.VERSION_FIELD -> StandardEntitySchema.VERSION_COLUMN;
            case StandardEntitySchema.DELETED_FIELD -> StandardEntitySchema.DELETED_COLUMN;
            case StandardEntitySchema.DELETED_AT_FIELD -> StandardEntitySchema.DELETED_AT_COLUMN;
            case StandardEntitySchema.DELETED_BY_FIELD -> StandardEntitySchema.DELETED_BY_COLUMN;
            case StandardEntitySchema.CREATED_BY_FIELD -> StandardEntitySchema.CREATED_BY_COLUMN;
            case StandardEntitySchema.CREATED_AT_FIELD -> StandardEntitySchema.CREATED_AT_COLUMN;
            case StandardEntitySchema.UPDATED_BY_FIELD -> StandardEntitySchema.UPDATED_BY_COLUMN;
            case StandardEntitySchema.UPDATED_AT_FIELD -> StandardEntitySchema.UPDATED_AT_COLUMN;
            case net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.TITLE_FIELD ->
                    net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.TITLE_COLUMN;
            case net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.ENABLED_FIELD ->
                    net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.ENABLED_COLUMN;
            case net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.SORT_FIELD ->
                    net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.SORT_COLUMN;
            case net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.TREE_PARENT_FIELD ->
                    net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.TREE_PARENT_COLUMN;
            default -> entity.fields().stream()
                    .filter(field -> field.fieldName().equals(fieldName))
                    .map(FieldDefinition::columnName)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("projection reference field is not declared: "
                            + entity.alias() + "." + fieldName));
        };
    }

    private static String qualifiedTable(EntityDefinition entity, DBInfo.Type databaseType) {
        return qualifiedTable(entity.schemaName(), entity.tableName(), databaseType);
    }

    private static String qualifiedTable(String schemaName, String tableName, DBInfo.Type databaseType) {
        String schema = schemaName == null || schemaName.isBlank() ? EntityDefinition.DEFAULT_SCHEMA_NAME : schemaName;
        return quote(schema, databaseType) + "." + quote(tableName, databaseType);
    }

    static String qualified(String tableAlias, String columnName, DBInfo.Type databaseType) {
        return quote(tableAlias, databaseType) + "." + quote(columnName, databaseType);
    }

    private static String selectExpression(SelectField field, DBInfo.Type databaseType) {
        if (field.expression() != null) {
            return field.expression();
        }
        return qualified(field.tableAlias(), field.columnName(), databaseType);
    }

    static String quote(String identifier, DBInfo.Type databaseType) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("invalid SQL identifier: " + identifier);
        }
        if (databaseType == DBInfo.Type.MYSQL) {
            return "`" + identifier + "`";
        }
        return "\"" + identifier + "\"";
    }

    private record SelectField(String tableAlias, String columnName, String outputName, String expression) {
        private SelectField(String tableAlias, String columnName, String outputName) {
            this(tableAlias, columnName, outputName, null);
        }

        private static SelectField expression(String expression, String outputName) {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("projection select expression must not be blank");
            }
            return new SelectField(null, null, outputName, expression);
        }

        private boolean sameSource(SelectField other) {
            return java.util.Objects.equals(tableAlias, other.tableAlias)
                    && java.util.Objects.equals(columnName, other.columnName)
                    && java.util.Objects.equals(expression, other.expression);
        }
    }
}
