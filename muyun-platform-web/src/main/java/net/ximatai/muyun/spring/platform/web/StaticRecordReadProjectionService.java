package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class StaticRecordReadProjectionService {
    private final StaticModuleDefinitionCatalog staticModuleDefinitionCatalog;
    private final RelationProjectionReadService relationProjectionReadService;
    private final OptionSourceRegistry optionSourceRegistry;

    public StaticRecordReadProjectionService(StaticModuleDefinitionCatalog staticModuleDefinitionCatalog) {
        this(staticModuleDefinitionCatalog, (RelationProjectionReadService) null, null);
    }

    @Autowired
    public StaticRecordReadProjectionService(StaticModuleDefinitionCatalog staticModuleDefinitionCatalog,
                                             ObjectProvider<RelationProjectionReadService> relationProjectionReadService,
                                             ObjectProvider<OptionSourceRegistry> optionSourceRegistry) {
        this(staticModuleDefinitionCatalog,
                relationProjectionReadService == null ? null : relationProjectionReadService.getIfAvailable(),
                optionSourceRegistry == null ? null : optionSourceRegistry.getIfAvailable());
    }

    StaticRecordReadProjectionService(StaticModuleDefinitionCatalog staticModuleDefinitionCatalog,
                                      RelationProjectionQueryExecutor projectionQueryExecutor,
                                      RelationProjectionDatabaseTypeProvider databaseTypeProvider) {
        this(staticModuleDefinitionCatalog, projectionQueryExecutor, databaseTypeProvider, null);
    }

    StaticRecordReadProjectionService(StaticModuleDefinitionCatalog staticModuleDefinitionCatalog,
                                      RelationProjectionQueryExecutor projectionQueryExecutor,
                                      RelationProjectionDatabaseTypeProvider databaseTypeProvider,
                                      OptionSourceRegistry optionSourceRegistry) {
        this(staticModuleDefinitionCatalog,
                new RelationProjectionReadService(projectionQueryExecutor, databaseTypeProvider),
                optionSourceRegistry);
    }

    private StaticRecordReadProjectionService(StaticModuleDefinitionCatalog staticModuleDefinitionCatalog,
                                              RelationProjectionReadService relationProjectionReadService,
                                              OptionSourceRegistry optionSourceRegistry) {
        this.staticModuleDefinitionCatalog = staticModuleDefinitionCatalog;
        this.relationProjectionReadService = relationProjectionReadService == null
                ? new RelationProjectionReadService()
                : relationProjectionReadService;
        this.optionSourceRegistry = optionSourceRegistry;
    }

    public <T> WebPageResponse<T> projectDefaultList(String moduleAlias,
                                                     WebPageResponse<T> response,
                                                     Object recordService) {
        RecordReadProjection projection = defaultListProjection(moduleAlias, recordService).orElse(null);
        if (projection == null) {
            return response;
        }
        return projectResponse(response, withReferenceSourceFields(moduleAlias, recordService, projection),
                modelClass(moduleAlias, recordService));
    }

    public boolean supportsDefaultListQuery(String moduleAlias, Object recordService) {
        StaticModuleDefinition definition = staticModuleDefinitionCatalog.find(moduleAlias).orElse(null);
        if (definition == null) {
            return false;
        }
        return defaultListProjection(moduleAlias, recordService)
                .filter(projection -> relationProjectionReadService
                        .describeListQuery(staticModuleDefinitionCatalog.definitions(), definition, projection)
                        .supported())
                .isPresent();
    }

    public boolean hasModuleDefinition(String moduleAlias) {
        if (moduleAlias == null) {
            return false;
        }
        Optional<StaticModuleDefinition> definition = staticModuleDefinitionCatalog.find(moduleAlias);
        return definition != null && definition.isPresent();
    }

    public Criteria queryCriteria(String moduleAlias, Object recordService, QueryRequest request) {
        QueryDescriptor descriptor = projectionAwareQueryDescriptor(moduleAlias, recordService);
        return new QueryCompiler(descriptor).criteria(request);
    }

    public Sort[] querySorts(String moduleAlias, Object recordService, QueryRequest request) {
        QueryDescriptor descriptor = projectionAwareQueryDescriptor(moduleAlias, recordService);
        return new QueryCompiler(descriptor).sorts(request);
    }

    public QuerySchema querySchema(String moduleAlias, Object recordService) {
        return QuerySchema.from(projectionAwareQueryDescriptor(moduleAlias, recordService),
                modelClass(moduleAlias, recordService));
    }

    /**
     * Executes the shared static list-read pipeline. Standard and recycle-bin reads differ only by
     * their explicit record visibility and corresponding action policy.
     */
    public Optional<WebPageResponse<Map<String, Object>>> queryDefaultList(String moduleAlias,
                                                                           QueryRequest request,
                                                                           Criteria additionalCriteria,
                                                                           PageRequest pageRequest,
                                                                           CrudAbility<?> recordService,
                                                                           ActionExecutionPolicy actionPolicy,
                                                                           RecordReadVisibility visibility) {
        if (moduleAlias == null || recordService == null || actionPolicy == null || visibility == null
                || !supportsDefaultListQuery(moduleAlias, recordService)) {
            return Optional.empty();
        }
        if (!visibility.action().matches(actionPolicy.actionCode())) {
            throw new IllegalArgumentException("record visibility does not match query action: "
                    + visibility + " / " + actionPolicy.actionCode());
        }
        Criteria criteria = andCriteria(queryCriteria(moduleAlias, recordService, request), additionalCriteria);
        Sort[] sorts = querySorts(moduleAlias, recordService, request);
        if (recordService instanceof DataScopeAbility<?> dataScopeAbility) {
            DataScopeCriteriaResult scope = dataScopeAbility.readScopeByPolicy(actionPolicy, criteria);
            return dataScopeAbility.withDataScopeTenant(scope,
                    () -> queryDefaultList(moduleAlias,
                            visibility.apply(recordService, scope.criteria()),
                            pageRequest, recordService, sorts));
        }
        return queryDefaultList(
                moduleAlias, visibility.apply(recordService, criteria), pageRequest, recordService, sorts);
    }

    public Optional<WebPageResponse<Map<String, Object>>> queryDefaultList(String moduleAlias,
                                                                           QueryRequest request,
                                                                           PageRequest pageRequest,
                                                                           CrudAbility<?> recordService,
                                                                           ActionExecutionPolicy actionPolicy,
                                                                           RecordReadVisibility visibility) {
        return queryDefaultList(moduleAlias, request, Criteria.of(), pageRequest, recordService, actionPolicy, visibility);
    }

    private static Criteria andCriteria(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria criteria = Criteria.of();
        criteria.andGroup(first.getRoot());
        criteria.andGroup(second.getRoot());
        return criteria;
    }

    public Optional<WebPageResponse<Map<String, Object>>> queryDefaultList(String moduleAlias,
                                                                           Criteria criteria,
                                                                           PageRequest pageRequest,
                                                                           Object recordService,
                                                                           Sort... sorts) {
        StaticModuleDefinition definition = staticModuleDefinitionCatalog.find(moduleAlias).orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        if (compilation == null || compilation.uiDescriptor() == null || compilation.readModel() == null) {
            return Optional.empty();
        }
        RecordReadProjection projection = withReferenceSourceFields(moduleAlias, recordService, RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                recordService,
                ActionExecutionContextHolder.current().orElse(null)));
        PageResult<Map<String, Object>> page = relationProjectionReadService.queryList(
                staticModuleDefinitionCatalog.definitions(),
                definition,
                projection,
                criteria,
                pageRequest,
                sorts
        ).orElse(null);
        if (page == null) {
            return Optional.empty();
        }
        Class<?> modelClass = modelClass(moduleAlias, recordService);
        List<Map<String, Object>> records = postProcessStaticOutput(
                modelClass, projection, page.getRecords());
        WebPageResponse<Map<String, Object>> response = new WebPageResponse<>(
                records,
                page.getTotal(),
                page.getPageNum(),
                page.getPageSize(),
                page.getPages(),
                page.isTotalKnown(),
                null
        );
        return Optional.of(response);
    }

    public Optional<WebPageResponse<Map<String, Object>>> queryExplicitList(String moduleAlias,
                                                                            List<String> outputFields,
                                                                            Criteria criteria,
                                                                            PageRequest pageRequest,
                                                                            Object recordService,
                                                                            Sort... sorts) {
        return queryExplicitList(moduleAlias, "explicit_list", outputFields, criteria, pageRequest, recordService,
                sorts);
    }

    public Optional<WebPageResponse<Map<String, Object>>> queryExplicitList(String moduleAlias,
                                                                            String viewCode,
                                                                            List<String> outputFields,
                                                                            Criteria criteria,
                                                                            PageRequest pageRequest,
                                                                            Object recordService,
                                                                            Sort... sorts) {
        StaticModuleDefinition definition = staticModuleDefinitionCatalog.find(moduleAlias).orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        if (compilation == null || compilation.readModel() == null) {
            return Optional.empty();
        }
        RecordReadProjection projection = withReferenceSourceFields(moduleAlias, recordService, RecordReadProjectionPlanner.explicit(
                moduleAlias,
                compilation.readModel(),
                viewCode,
                outputFields,
                recordService,
                ActionExecutionContextHolder.current().orElse(null)));
        PageResult<Map<String, Object>> page = relationProjectionReadService.queryList(
                staticModuleDefinitionCatalog.definitions(),
                definition,
                projection,
                criteria,
                pageRequest,
                sorts
        ).orElse(null);
        if (page == null) {
            return Optional.empty();
        }
        Class<?> modelClass = modelClass(moduleAlias, recordService);
        List<Map<String, Object>> records = postProcessStaticOutput(
                modelClass, projection, page.getRecords());
        WebPageResponse<Map<String, Object>> response = new WebPageResponse<>(
                records,
                page.getTotal(),
                page.getPageNum(),
                page.getPageSize(),
                page.getPages(),
                page.isTotalKnown(),
                null
        );
        return Optional.of(response);
    }

    private Class<?> modelClass(String moduleAlias, Object recordService) {
        if (recordService instanceof CrudAbility<?> crudAbility) {
            Class<?> modelClass = crudAbility.modelClass();
            if (modelClass != null) {
                return modelClass;
            }
        }
        return staticModuleDefinitionCatalog.find(moduleAlias)
                .map(StaticModuleDefinition::modelClass)
                .orElse(null);
    }

    private QueryDescriptor projectionAwareQueryDescriptor(String moduleAlias, Object recordService) {
        QueryDescriptor base = recordService instanceof QueryAbility<?> queryAbility
                ? queryAbility.queryDescriptor()
                : QueryDescriptor.builder(moduleAlias).build();
        QueryDescriptor.Builder builder = QueryDescriptor.builder(base.scopeName());
        base.fields().forEach(builder::field);
        for (String key : base.externalCriteriaKeys()) {
            builder.externalCriteria(key, base.externalCriteriaResolver(key));
        }
        for (Sort sort : base.defaultSorts()) {
            builder.defaultSort(sort);
        }
        staticModuleDefinitionCatalog.find(moduleAlias)
                .stream()
                .flatMap(definition -> definition.readProjections().stream())
                .filter(projection -> projection.filterable() || projection.sortable())
                .forEach(projection -> builder.field(queryField(moduleAlias, recordService, projection)));
        return builder.build();
    }

    private QueryField queryField(String moduleAlias,
                                  Object recordService,
                                  StaticModuleReadProjectionDefinition projection) {
        QueryField field = projection.projectionType() == ModuleReadProjection.ProjectionType.EXISTS
                ? QueryField.of(projection.outputField(), QueryValueType.BOOLEAN, QueryOperator.EQ)
                : QueryDescriptors.field(modelClass(moduleAlias, recordService), projection.outputField());
        if (!projection.filterable()) {
            return new QueryField(
                    field.fieldName(),
                    field.title(),
                    field.valueType(),
                    Set.of(),
                    null,
                    projection.sortable(),
                    false,
                    field.optionBinding(),
                    field.selectionMode(),
                    field.optionTitleField()
            );
        }
        if (projection.sortable()) {
            field = field.withSortable();
        }
        return field;
    }

    private Optional<RecordReadProjection> defaultListProjection(String moduleAlias, Object recordService) {
        if (moduleAlias == null || moduleAlias.isBlank()) {
            return Optional.empty();
        }
        return staticModuleDefinitionCatalog.find(moduleAlias)
                .map(ModuleUiDescriptorCompiler::compileModule)
                .filter(compilation -> compilation.uiDescriptor() != null && compilation.readModel() != null)
                .map(compilation -> RecordReadProjectionPlanner.defaultList(
                        compilation.uiDescriptor(),
                        compilation.readModel(),
                        recordService,
                        ActionExecutionContextHolder.current().orElse(null)
                ));
    }

    private List<String> outputFieldNames(RecordReadProjection projection, Class<?> modelClass) {
        LinkedHashSet<String> fields = projection.outputFields().stream()
                .map(ViewFieldRef::fieldName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (modelClass != null) {
            Set<String> optionLoadFields = projection.postReadTransforms().stream()
                    .map(RecordReadPostTransform::parse)
                    .flatMap(Optional::stream)
                    .filter(RecordReadPostTransform::isOptionLoad)
                    .map(RecordReadPostTransform::fieldName)
                    .collect(java.util.stream.Collectors.toSet());
            OptionLoadResolver.resolve(modelClass).stream()
                    .filter(definition -> optionLoadFields.contains(definition.outputField()))
                    .map(definition -> definition.outputField())
                    .forEach(fields::add);
        }
        return List.copyOf(fields);
    }

    private RecordReadProjection withReferenceSourceFields(String moduleAlias,
                                                            Object recordService,
                                                            RecordReadProjection projection) {
        Class<?> modelClass = modelClass(moduleAlias, recordService);
        if (modelClass == null || projection == null) {
            return projection;
        }
        Set<String> output = Set.copyOf(outputFieldNames(projection, modelClass));
        List<String> internal = new java.util.ArrayList<>(projection.internalReadFields());
        for (ReferencePlan plan : StaticReferenceResolver.plans(modelClass)) {
            boolean projectionRequested = plan.projections().stream()
                    .anyMatch(item -> output.contains(item.outputField()));
            if (projectionRequested && !output.contains(plan.sourceField())) {
                internal.add(plan.sourceField());
            }
        }
        for (net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath path
                : StaticReferenceResolver.loadPaths(modelClass)) {
            if (output.contains(path.outputField()) && !output.contains(path.sourceField())) {
                internal.add(path.sourceField());
            }
        }
        for (net.ximatai.muyun.spring.ability.reference.ReferenceSummaryPlan summary
                : StaticReferenceResolver.summaryPlans(modelClass)) {
            if (output.contains(summary.outputField()) && !output.contains(summary.sourceField())) {
                internal.add(summary.sourceField());
            }
        }
        for (net.ximatai.muyun.spring.common.option.OptionLoadDefinition load
                : OptionLoadResolver.resolve(modelClass)) {
            if (output.contains(load.outputField()) && !output.contains(load.sourceField())) {
                internal.add(load.sourceField());
            }
        }
        List<String> normalizedInternal = internal.stream().distinct().toList();
        if (normalizedInternal.equals(projection.internalReadFields())) {
            return projection;
        }
        return new RecordReadProjection(projection.moduleAlias(), projection.viewCode(), projection.actionCode(),
                projection.permissionCode(), projection.permissionActionCode(), projection.fieldReadPolicies(),
                projection.outputFields(), normalizedInternal, projection.postReadTransforms());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> WebPageResponse<T> projectResponse(WebPageResponse<T> response,
                                                   RecordReadProjection projection,
                                                   Class<?> modelClass) {
        List<Map<String, Object>> records = postProcessStaticOutput(modelClass, projection,
                RecordReadProjectionProjector.projectWithInternalFields(response.records(), projection));
        return new WebPageResponse(
                records,
                response.total(),
                response.pageNum(),
                response.pageSize(),
                response.pages(),
                response.totalKnown(),
                response.navigation()
        );
    }

    private List<Map<String, Object>> postProcessStaticOutput(Class<?> modelClass,
                                                              RecordReadProjection projection,
                                                              List<Map<String, Object>> records) {
        return ReferenceReadProjectionPostProcessor.apply(modelClass,
                RecordReadProjectionPostProcessor.applyStaticOutput(
                        modelClass,
                        projection,
                        records,
                        optionSourceRegistry),
                outputFieldNames(projection, modelClass));
    }
}
