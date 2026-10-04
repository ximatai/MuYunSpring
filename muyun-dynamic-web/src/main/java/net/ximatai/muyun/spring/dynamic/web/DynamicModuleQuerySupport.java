package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.query.QueryLikePattern;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryGroupOperator;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlan;
import net.ximatai.muyun.spring.platform.web.ModuleQueryFormField;
import net.ximatai.muyun.spring.platform.web.ModuleQueryTemplatePlan;
import net.ximatai.muyun.spring.platform.web.PageContextScopePolicy;
import net.ximatai.muyun.spring.platform.web.PageContextTarget;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.WebSort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared published-plan query contract for dynamic lists and exchange exports. */
final class DynamicModuleQuerySupport {
    Criteria criteria(ModuleExecutionPlan plan, WebQueryRequest request,
                      Function<List<DynamicQueryCondition>, Criteria> compiler) {
        requireListUiConfig(plan, request == null ? null : request.uiConfigId());
        Criteria controls = controlsCriteria(plan, request, compiler);
        Criteria navigatorCriteria = request == null || !hasText(request.uiConfigId()) ? Criteria.of()
                : PageContextScopePolicy.criteria(plan.pageContextBindings().stream()
                        .filter(binding -> binding.target() == PageContextTarget.LIST_QUERY).toList(),
                        request.externalQueryValues(), false);
        return andCriteria(controls, navigatorCriteria);
    }

    /** Ordinary controls are shared by list and reference reads; caller owns their distinct scope. */
    Criteria controlsCriteria(ModuleExecutionPlan plan, WebQueryRequest request,
                              Function<List<DynamicQueryCondition>, Criteria> compiler) {
        Criteria templateCriteria = templateCriteria(plan, request, compiler);
        List<DynamicQueryCondition> conditions = request == null ? List.of()
                : DynamicWebQueryMapper.queryConditions(request.conditions());
        validateConditions(plan.querySchema(), conditions);
        Criteria manualCriteria = conditions.isEmpty() ? Criteria.of() : compiler.apply(conditions);
        Criteria treeCriteria = request == null || request.criteria() == null ? Criteria.of()
                : DynamicWebQueryMapper.queryCriteria(request.criteria(), nested -> {
                    validateConditions(plan.querySchema(), nested);
                    return compiler.apply(nested);
                });
        Criteria queryFormCriteria = queryFormCriteria(request, plan.queryFormFields(), compiler);
        Criteria quickCriteria = quickSearchCriteria(request, plan.querySchema());
        return andCriteria(templateCriteria, queryFormCriteria, manualCriteria, treeCriteria, quickCriteria);
    }

    Criteria templateCriteria(ModuleExecutionPlan plan, WebQueryRequest request,
                              Function<List<DynamicQueryCondition>, Criteria> compiler) {
        if (request == null || !hasText(request.queryTemplateId())) {
            return Criteria.of();
        }
        if (!plan.queryTemplateIds().contains(request.queryTemplateId())) {
            throw new PlatformException("Query template is not enabled by module execution plan: "
                    + request.queryTemplateId());
        }
        ModuleQueryTemplatePlan template = plan.queryTemplates().stream()
                .filter(candidate -> candidate.templateId().equals(request.queryTemplateId())).findFirst()
                .orElseThrow(() -> new PlatformException("Query template has no compiled execution facts: "
                        + request.queryTemplateId()));
        return compiledTemplateGroup(template.nodes(),
                PlatformQueryGroupOperator.AND,
                request.externalQueryValues(), compiler);
    }

    private void validateConditions(QuerySchema schema, List<DynamicQueryCondition> conditions) {
        Map<String, QuerySchema.Field> fields = schema.fields().stream()
                .collect(java.util.stream.Collectors.toMap(QuerySchema.Field::name, field -> field, (left, right) -> left));
        for (DynamicQueryCondition condition : conditions) {
            QuerySchema.Field field = fields.get(condition.fieldName());
            if (field == null) throw new PlatformException("Query field is not enabled by module execution plan: "
                    + condition.fieldName());
            if (condition.operator() != null && !field.operators().contains(
                    QueryOperator.valueOf(condition.operator().name()))) {
                throw new PlatformException("Query operator is not enabled by module execution plan: "
                        + condition.fieldName() + "." + condition.operator());
            }
        }
    }

    Criteria queryFormCriteria(WebQueryRequest request, List<ModuleQueryFormField> fields,
                              Function<List<DynamicQueryCondition>, Criteria> compiler) {
        if (request == null || request.queryForm().isEmpty()) return Criteria.of();
        Map<String, ModuleQueryFormField> byName = fields.stream().collect(java.util.stream.Collectors.toMap(
                ModuleQueryFormField::fieldName, field -> field, (left, right) -> left));
        List<DynamicQueryCondition> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : request.queryForm().entrySet()) {
            if (DynamicWebQueryFormSupport.isEmptyValue(entry.getValue())) continue;
            ModuleQueryFormField field = byName.get(entry.getKey() == null ? null : entry.getKey().trim());
            if (field == null) throw new PlatformException("Query form field is not enabled by module execution plan: " + entry.getKey());
            DynamicQueryCondition condition = DynamicWebQueryFormSupport.condition(field, entry.getValue());
            if (condition != null) {
                conditions.add(condition);
            }
        }
        return conditions.isEmpty() ? Criteria.of() : compiler.apply(conditions);
    }

    Criteria quickSearchCriteria(WebQueryRequest request, QuerySchema schema) {
        if (request == null || !hasText(request.quickSearch())) return Criteria.of();
        List<String> fields = request.quickSearchFields().isEmpty() ? schema.quickSearch().fields()
                : request.quickSearchFields();
        if (fields.isEmpty() || fields.stream().anyMatch(field -> !schema.quickSearch().fields().contains(field))) {
            String invalid = fields.stream().filter(field -> !schema.quickSearch().fields().contains(field))
                    .findFirst().orElse(null);
            throw new PlatformException("Quick search field is not enabled by module execution plan: " + invalid);
        }
        Criteria criteria = Criteria.of();
        criteria.andGroup(group -> fields.forEach(field -> group.orLikeIgnoreCase(field, QueryLikePattern.containsLiteral(request.quickSearch().trim()))));
        return criteria;
    }

    private Criteria compiledTemplateGroup(List<ModuleQueryTemplatePlan.Node> nodes,
                                           PlatformQueryGroupOperator operator,
                                           Map<String, ?> externalValues,
                                          Function<List<DynamicQueryCondition>, Criteria> compiler) {
        Criteria criteria = Criteria.of();
        boolean first = true;
        for (ModuleQueryTemplatePlan.Node node : nodes) {
            Criteria child = node.group() ? compiledTemplateGroup(node.children(), node.groupOperator(), externalValues, compiler)
                    : compiledTemplateLeaf(node, externalValues, compiler);
            if (child.isEmpty()) continue;
            if (first || operator == PlatformQueryGroupOperator.AND) {
                criteria.andGroup(child.getRoot());
            } else {
                criteria.orGroup(child.getRoot());
            }
            first = false;
        }
        return criteria;
    }

    private Criteria compiledTemplateLeaf(ModuleQueryTemplatePlan.Node node, Map<String, ?> externalValues,
                                         Function<List<DynamicQueryCondition>, Criteria> compiler) {
        Object value = node.externalValueKey() != null && externalValues.containsKey(node.externalValueKey())
                ? externalValues.get(node.externalValueKey()) : node.defaultValue();
        boolean noValue = node.operator() == DynamicQueryOperator.NULL
                || node.operator() == DynamicQueryOperator.NOT_NULL
                || node.operator() == DynamicQueryOperator.EMPTY
                || node.operator() == DynamicQueryOperator.NOT_EMPTY;
        if (!noValue && (value == null || value instanceof String text && text.isBlank())) return Criteria.of();
        return compiler.apply(List.of(new DynamicQueryCondition(node.fieldName(), node.operator(),
                noValue ? List.of() : value instanceof java.util.Collection<?> values ? List.copyOf(values) : List.of(value),
                node.timeZone())));
    }

    void requireListUiConfig(ModuleExecutionPlan plan,
                             String uiConfigId) {
        requireUiConfig(plan.moduleAlias(), plan.listUiConfigId(), uiConfigId, "LIST", "Query");
    }

    void requireUiConfig(String moduleAlias, String expectedUiConfigId, String actualUiConfigId,
                         String uiType, String operation) {
        if (expectedUiConfigId == null) return;
        if (!hasText(actualUiConfigId)) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    operation + " requires published " + uiType + " uiConfigId from module execution plan: "
                            + expectedUiConfigId);
        }
        if (!expectedUiConfigId.equals(actualUiConfigId)) {
            throw PlatformErrors.conflict(PlatformErrorCodes.CONFLICT_VERSION,
                    operation + " uiConfigId does not match the published " + uiType + " plan: "
                            + expectedUiConfigId,
                    ErrorScope.module(moduleAlias),
                    Map.of("expectedUiConfigId", expectedUiConfigId, "actualUiConfigId", actualUiConfigId));
        }
    }

    static Criteria andCriteria(Criteria... criteriaList) {
        Criteria single = null;
        int size = 0;
        for (Criteria item : criteriaList) {
            if (item != null && !item.isEmpty()) {
                single = item;
                size++;
            }
        }
        if (size == 0) {
            return Criteria.of();
        }
        if (size == 1) {
            return single;
        }
        Criteria criteria = Criteria.of();
        for (Criteria item : criteriaList) {
            if (item != null && !item.isEmpty()) {
                criteria.andGroup(item.getRoot());
            }
        }
        return criteria;
    }

    void validateSorts(QuerySchema schema, List<WebSort> sorts,
                       Set<String> projectionSorts) {
        Set<String> sortable = new LinkedHashSet<>(projectionSorts == null ? Set.of() : projectionSorts);
        schema.fields().stream().filter(QuerySchema.Field::sortable).map(QuerySchema.Field::name).forEach(sortable::add);
        for (var sort : sorts) {
            if (sort == null || !sortable.contains(sort.field())) {
                throw new PlatformException("Sort field is not enabled by module execution plan: "
                        + (sort == null ? null : sort.field()));
            }
        }
    }

    Set<String> runtimeSortFields(DynamicModuleDescriptor module) {
        if (module == null) return Set.of();
        return module.entities().stream()
                .filter(entity -> module.mainEntityAlias().equals(entity.entityAlias()))
                .filter(entity -> entity.capabilities().contains(EntityCapability.SORT.name()))
                .filter(entity -> entity.fields().stream().anyMatch(field ->
                        PlatformAbilityFields.SORT_FIELD.equals(field.fieldName()) && field.sortable()))
                .findFirst()
                .map(ignored -> Set.of(PlatformAbilityFields.SORT_FIELD))
                .orElseGet(Set::of);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
