package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import org.springframework.http.HttpStatus;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CrudWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends QueryViewWeb<T, S>, RecordLabelWeb<T>, StaticModuleServiceDeclaration {
    @Override
    default CrudAbility<?> staticModuleService() {
        return service();
    }

    default PageResult<T> queryRecords(WebQueryRequest request) {
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            PageResult<T> result = (PageResult<T>) dataScopeAbility.pageQueryForAction(
                    PlatformAction.QUERY, queryCriteria(request), pageRequest, querySorts(request));
            return result;
        }
        return service().pageQuery(queryCriteria(request), pageRequest, querySorts(request));
    }

    default StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return null;
    }

    default List<T> queryListRecords(WebQueryRequest request) {
        requireUnpagedQuerySupported(request);
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) dataScopeAbility.listForAction(
                    PlatformAction.QUERY, queryCriteria(request), querySorts(request));
            return result;
        }
        return service().list(queryCriteria(request), querySorts(request));
    }

    default boolean supportsUnpagedQuery() {
        return false;
    }

    default void requireUnpagedQuerySupported(WebQueryRequest request) {
        if (!supportsUnpagedQuery()) {
            throw new IllegalArgumentException("unpaged query is not supported by " + webScopeName());
        }
        if (request != null && request.page() != null) {
            throw new IllegalArgumentException("unpaged query cannot specify page");
        }
        if (request != null && request.navigationSessionEnabled()) {
            throw new IllegalArgumentException("unpaged query navigation is not supported by " + webScopeName());
        }
    }

    default Criteria queryCriteria(WebQueryRequest request) {
        Criteria workspaceCriteria = navigatorCriteria(request);
        if (service() instanceof QueryAbility<?> queryAbility) {
            Criteria criteria = queryAbility.queryCriteria(WebQueryRequests.from(request));
            return andCriteria(criteria, workspaceCriteria);
        }
        if (request != null && !request.conditions().isEmpty()) {
            throw new IllegalArgumentException("query conditions are not supported by " + webScopeName());
        }
        if (request != null && request.criteria() != null && !request.criteria().isEmpty()) {
            throw new IllegalArgumentException("query criteria are not supported by " + webScopeName());
        }
        return workspaceCriteria;
    }

    default Sort[] querySorts(WebQueryRequest request) {
        if (service() instanceof QueryAbility<?> queryAbility) {
            Sort[] sorts = queryAbility.querySorts(WebQueryRequests.from(request));
            return sorts == null ? new Sort[0] : sorts;
        }
        if (request != null && !request.sorts().isEmpty()) {
            throw new IllegalArgumentException("query sorts are not supported by " + webScopeName());
        }
        if (service() instanceof SortAbility<?>) {
            return new Sort[]{Sort.asc(PlatformAbilityFields.SORT_FIELD)};
        }
        return new Sort[0];
    }

    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    default QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return webScope(() -> {
            StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
            if (projectionService != null && this instanceof StaticModuleUiContributor contributor
                    && isCurrentModuleUiDefinition(contributor)
                    && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
                return withNavigatorCriteria(
                        projectionService.querySchema(contributor.moduleUiDefinition().moduleAlias(), service()), uiConfigId);
            }
            if (service() instanceof QueryAbility<?> queryAbility) {
                return withNavigatorCriteria(queryAbility.querySchema(), uiConfigId);
            }
            throw new IllegalArgumentException("query schema is not supported by " + webScopeName());
        });
    }

    private Criteria navigatorCriteria(WebQueryRequest request) {
        if (request == null || request.externalQueryValues() == null) return Criteria.of();
        Criteria criteria = Criteria.of();
        for (PageNavigatorQueryBindingDefinition binding : navigatorQueryBindings(request.uiConfigId())) {
            Object selectedValue = request.externalQueryValues().get(binding.queryCriteriaKey());
            if (selectedValue != null) criteria.eq(binding.field(), selectedValue);
        }
        return criteria;
    }

    private QuerySchema withNavigatorCriteria(QuerySchema schema, String uiConfigId) {
        List<PageNavigatorQueryBindingDefinition> bindings = navigatorQueryBindings(uiConfigId);
        if (bindings.isEmpty()) return schema;
        List<QuerySchema.ExternalCriteria> externalCriteria = new ArrayList<>(schema.externalCriteria());
        for (PageNavigatorQueryBindingDefinition binding : bindings) {
            if (externalCriteria.stream().noneMatch(criteria -> binding.queryCriteriaKey().equals(criteria.key()))) {
                externalCriteria.add(new QuerySchema.ExternalCriteria(binding.queryCriteriaKey(), "OBJECT", "PAGE_CONTEXT"));
            }
        }
        return new QuerySchema(schema.scopeName(), schema.entityAlias(), schema.quickSearch(), schema.fields(),
                externalCriteria, schema.defaultSorts());
    }

    private List<PageNavigatorQueryBindingDefinition> navigatorQueryBindings(String uiConfigId) {
        if (!(this instanceof StaticModuleUiContributor contributor) || !isCurrentModuleUiDefinition(contributor)) {
            return List.of();
        }
        ModulePageDefinition page = contributor.moduleUiDefinition().page();
        PageNavigatorDefinition navigator = switch (page) {
            case ListDetailCardPageDefinition card -> card.navigator();
            case FlatManagementPageDefinition flat -> flat.navigator();
            case TreeManagementPageDefinition ignored -> null;
            case null -> null;
        };
        if (navigator == null) return List.of();
        return navigator.levels().stream().flatMap(level -> level.queryBindings().stream()).toList();
    }

    private Criteria andCriteria(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria criteria = Criteria.of();
        criteria.andGroup(first.getRoot());
        criteria.andGroup(second.getRoot());
        return criteria;
    }

    @GetMapping("/form/schema")
    @ActionEndpoint(PlatformAction.VIEW)
    default FormSchema formSchema(@RequestParam(required = false) String resource,
                                  @RequestParam(required = false) String editorSurface) {
        return webScope(() -> {
            if (this instanceof StaticModuleUiContributor contributor) {
                if (isCurrentModuleUiDefinition(contributor)) {
                    String selectedResource = resource == null || resource.isBlank()
                            ? staticContributionResource() : resource;
                    FormSchema schema = ModuleUiFormSchemaAdapter.formSchema(contributor.moduleUiDefinition(),
                            formSchemaModelClass(), selectedResource, editorSurface);
                    if (schema != null) {
                        return schema;
                    }
                }
            }
            if (service() instanceof FormAbility<?> formAbility) {
                return formAbility.formSchema();
            }
            throw new IllegalArgumentException("form schema is not supported by " + webScopeName());
        });
    }

    private boolean isCurrentModuleUiDefinition(StaticModuleUiContributor contributor) {
        if (contributor.moduleUiDefinition() == null) return false;
        if (webScopeName().equals(contributor.moduleUiDefinition().moduleAlias())) return true;
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(getClass(), PlatformStaticActionContribution.class);
        return contribution != null && contribution.targetModule().equals(contributor.moduleUiDefinition().moduleAlias());
    }

    private Class<?> formSchemaModelClass() {
        Class<?> modelClass = service().modelClass();
        if (modelClass != null) {
            return modelClass;
        }
        return ResolvableType.forClass(CrudWeb.class, getClass()).resolveGeneric(0);
    }

    private String staticContributionResource() {
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(getClass(), PlatformStaticActionContribution.class);
        return contribution == null ? null : contribution.resource();
    }

    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    @SuppressWarnings("unchecked")
    default WebPageResponse<T> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            if (request == null || !request.unpagedEnabled()) {
                Optional<WebPageResponse<Map<String, Object>>> projected = queryStaticProjectedList(
                        request, RecordReadVisibility.ACTIVE);
                if (projected.isPresent()) {
                    return (WebPageResponse<T>) (WebPageResponse<?>) projected.get();
                }
            }
            WebPageResponse<T> response;
            if (request != null && request.unpagedEnabled()) {
                List<T> records = WebOutputSupport.records(service(), queryListRecords(request), FieldOutputContext.LIST);
                response = WebPageResponse.fromList(records);
            } else {
                response = WebPageResponse.from(WebOutputSupport.page(service(), queryRecords(request), FieldOutputContext.LIST));
            }
            return projectStaticDefaultList(response);
        });
    }

    default Optional<WebPageResponse<Map<String, Object>>> queryStaticProjectedList(
            WebQueryRequest request, RecordReadVisibility visibility) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService == null || !(this instanceof StaticModuleUiContributor contributor)
                || visibility == null) {
            return Optional.empty();
        }
        String moduleAlias = contributor.moduleUiDefinition().moduleAlias();
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        return projectionService.queryDefaultList(
                moduleAlias,
                WebQueryRequests.from(request),
                navigatorCriteria(request),
                PageRequest.of(page.pageNum(), page.pageSize()),
                service(),
                StaticStandardMutationSupport.actionPolicy(this, visibility.action()),
                visibility
        );
    }

    default WebPageResponse<T> projectStaticDefaultList(WebPageResponse<T> response) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService == null || !(this instanceof StaticModuleUiContributor contributor)) {
            return response;
        }
        return projectionService.projectDefaultList(contributor.moduleUiDefinition().moduleAlias(), response, service());
    }

    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    default T view(@PathVariable String id) {
        return webScope(() -> WebOutputSupport.record(service(),
                StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id),
                FieldOutputContext.VIEW));
    }

    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @StandardMutation(StandardMutationKind.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    default T insert(@RequestBody T record) {
        return MutationTenantScopeExecutor.forCreate(this, record, () -> webScope(() -> {
            String id = service().insert(record);
            T saved = WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
            StandardMutationResultSupport.created(this, id, recordLabel(saved));
            return saved;
        }));
    }

    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.UPDATE)
    @Transactional
    default T update(@PathVariable String id, @RequestBody T record) {
        record.setId(id);
        return MutationTenantScopeExecutor.forUpdate(this, id, record, () -> webScope(() -> {
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.UPDATE, id);
            service().update(record);
            T saved = WebOutputSupport.record(service(),
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id),
                    FieldOutputContext.VIEW);
            StandardMutationResultSupport.updated(this, id, recordLabel(saved));
            return saved;
        }));
    }

    @PostMapping("/delete/{id}")
    @ActionEndpoint(PlatformAction.DELETE)
    @StandardMutation(StandardMutationKind.DELETE)
    default int delete(@PathVariable String id, @RequestBody RecordActionWebRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.DELETE, id);
            T existing = service().select(id);
            return StandardMutationResultSupport.deleted(this, id, recordLabel(existing),
                    () -> service().delete(id, request.version()));
        }));
    }
}
