package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.time.BusinessTimeContext;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldQueryDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicTemporalRangeCriteriaSupport;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformQueryItemService extends AbstractAbilityService<PlatformQueryItem> implements
        SoftDeleteAbility<PlatformQueryItem>,
        EnableAbility<PlatformQueryItem>,
        SortAbility<PlatformQueryItem>,
        QueryAbility<PlatformQueryItem> {
    public static final String MODULE_ALIAS = "platform.query_item";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformQueryTemplateService queryTemplateService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final FieldSpecService fieldTypeService;
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler;
    private final PlatformTimeService timeService;

    public PlatformQueryItemService(BaseDao<PlatformQueryItem, String> queryItemDao,
                                    PlatformQueryTemplateService queryTemplateService,
                                    ModuleMetadataFieldService moduleFieldService,
                                    FieldSpecService fieldTypeService) {
        this(queryItemDao, queryTemplateService, moduleFieldService, fieldTypeService, null, PlatformAbilityRuntime.timeService());
    }

    @Autowired
    public PlatformQueryItemService(BaseDao<PlatformQueryItem, String> queryItemDao,
                                    PlatformQueryTemplateService queryTemplateService,
                                    ModuleMetadataFieldService moduleFieldService,
                                    FieldSpecService fieldTypeService,
                                    MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                    ObjectProvider<PlatformTimeService> timeServiceProvider) {
        this(queryItemDao, queryTemplateService, moduleFieldService, fieldTypeService, fieldDefinitionCompiler,
                timeServiceProvider == null ? PlatformAbilityRuntime.timeService()
                        : timeServiceProvider.getIfAvailable(PlatformAbilityRuntime::timeService));
    }

    public PlatformQueryItemService(BaseDao<PlatformQueryItem, String> queryItemDao,
                                    PlatformQueryTemplateService queryTemplateService,
                                    ModuleMetadataFieldService moduleFieldService,
                                    FieldSpecService fieldTypeService,
                                    PlatformTimeService timeService) {
        this(queryItemDao, queryTemplateService, moduleFieldService, fieldTypeService, null, timeService);
    }

    public PlatformQueryItemService(BaseDao<PlatformQueryItem, String> queryItemDao,
                                    PlatformQueryTemplateService queryTemplateService,
                                    ModuleMetadataFieldService moduleFieldService,
                                    FieldSpecService fieldTypeService,
                                    MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                    PlatformTimeService timeService) {
        super(MODULE_ALIAS, PlatformQueryItem.class, queryItemDao);
        this.queryTemplateService = queryTemplateService;
        this.moduleFieldService = moduleFieldService;
        this.fieldTypeService = fieldTypeService;
        this.fieldDefinitionCompiler = fieldDefinitionCompiler;
        this.timeService = java.util.Objects.requireNonNull(timeService, "timeService");
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformQueryItem.class, java.util.List.of("title", "parentId", "moduleMetadataFieldId", "operator", "allowExternalValue", "externalValueKey", "enabled"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public void beforeInsert(PlatformQueryItem item) {
        requireDraftQueryTemplate(item.getQueryTemplateId());
        normalizeAndValidate(item);
    }

    @Override
    public void beforeUpdate(PlatformQueryItem item) {
        PlatformQueryItem existing = selectIncludingDeleted(item.getId());
        requireDraftQueryTemplate(existing == null ? item.getQueryTemplateId() : existing.getQueryTemplateId());
        normalizeAndValidate(item);
        rejectChanged(existing, item, "Query item template", PlatformQueryItem::getQueryTemplateId);
    }

    @Override
    public void beforeDelete(String id) {
        PlatformQueryItem existing = select(id);
        if (existing != null) {
            requireDraftQueryTemplate(existing.getQueryTemplateId());
        }
    }

    public List<PlatformQueryItem> listByTemplateIds(List<String> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().in("queryTemplateId", templateIds)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<String> externalValueKeys(String queryTemplateId) {
        if (queryTemplateId == null || queryTemplateId.isBlank()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().eq("queryTemplateId", queryTemplateId)), ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD)).stream()
                .filter(item -> Boolean.TRUE.equals(item.getAllowExternalValue()))
                .map(PlatformQueryItem::getExternalValueKey)
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    public Criteria compile(String queryTemplateId) {
        return compile(queryTemplateId, Map.of());
    }

    public Criteria compile(String queryTemplateId, Map<String, ?> externalValues) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(queryTemplateId);
        List<PlatformQueryItem> items = list(enabledCriteria(Criteria.of().eq("queryTemplateId", template.getId())),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        Map<String, List<PlatformQueryItem>> childrenByParent = new HashMap<>();
        for (PlatformQueryItem item : items) {
            childrenByParent.computeIfAbsent(normalizeParentId(item.getParentId()), ignored -> new ArrayList<>()).add(item);
        }
        validateReachableTree(items, childrenByParent);
        Criteria criteria = Criteria.of();
        appendChildren(criteria, PlatformQueryGroupOperator.AND, childrenByParent.get(TreeAbility.ROOT_ID),
                childrenByParent, externalValues == null ? Map.of() : externalValues, new HashSet<>());
        return criteria;
    }

    private void normalizeAndValidate(PlatformQueryItem item) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(item.getQueryTemplateId());
        String parentId = normalizeParentId(item.getParentId());
        validateParent(item, template, parentId);
        item.setParentId(parentId);
        item.setQueryTemplateId(template.getId());
        if (item.getGroupOperator() == null) {
            item.setGroupOperator(PlatformQueryGroupOperator.AND);
        }
        if (isGroup(item)) {
            item.setOperator(null);
            item.setModuleMetadataFieldId(null);
            normalizeGroup(item);
            return;
        }
        normalizeLeaf(item, template);
    }

    private void validateParent(PlatformQueryItem item, PlatformQueryTemplate template, String parentId) {
        if (TreeAbility.ROOT_ID.equals(parentId)) {
            return;
        }
        PlatformQueryItem parent = select(parentId);
        if (parent == null || !Objects.equals(parent.getQueryTemplateId(), template.getId()) || !isGroup(parent)) {
            throw new PlatformException("Query item parent must be an existing group in the same template: " + parentId);
        }
        if (Objects.equals(parentId, item.getId())) {
            throw new PlatformException("Query item cannot use itself as parent: " + parentId);
        }
        rejectParentCycle(item, parent);
    }

    private void rejectParentCycle(PlatformQueryItem item, PlatformQueryItem parent) {
        Set<String> visited = new HashSet<>();
        PlatformQueryItem current = parent;
        while (current != null && !TreeAbility.ROOT_ID.equals(normalizeParentId(current.getParentId()))) {
            if (!visited.add(current.getId())) {
                throw new PlatformException("Query item parent cycle detected: " + current.getId());
            }
            if (Objects.equals(current.getParentId(), item.getId())) {
                throw new PlatformException("Query item cannot use descendant as parent: " + item.getId());
            }
            current = select(current.getParentId());
        }
    }

    private void normalizeGroup(PlatformQueryItem item) {
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            item.setTitle("Group");
        }
        item.setDefaultValue(null);
        item.setAllowExternalValue(Boolean.FALSE);
        item.setExternalValueKey(null);
    }

    private void normalizeLeaf(PlatformQueryItem item, PlatformQueryTemplate template) {
        ResolvedModuleMetadataField moduleField = moduleFieldService.resolve(item.getModuleMetadataFieldId());
        if (!Objects.equals(template.getModuleAlias(), moduleField.moduleAlias())) {
            throw new PlatformException("Query item requires module field in the same module: "
                    + template.getModuleAlias() + "." + moduleField.moduleAlias());
        }
        FieldSpec fieldType = fieldTypeService.requireFieldType(moduleField.fieldSpecAlias());
        FieldQueryDefinition queryDefinition = queryDefinition(moduleField, fieldType);
        if (!queryDefinition.queryable()) {
            throw new PlatformException("Query item field is not queryable: " + moduleField.fieldName());
        }
        DynamicQueryOperator operator = item.getOperator() == null
                ? queryDefinition.defaultOperator()
                : item.getOperator();
        if (!queryDefinition.operators().contains(operator)) {
            throw new PlatformException("Query item operator is not allowed: "
                    + moduleField.fieldName() + "." + operator);
        }
        item.setOperator(operator);
        item.setModuleMetadataFieldId(moduleField.moduleMetadataFieldId());
        if (item.getAllowExternalValue() == null) {
            item.setAllowExternalValue(Boolean.FALSE);
        }
        if (Boolean.TRUE.equals(item.getAllowExternalValue()) && hasText(item.getExternalValueKey())) {
            item.setExternalValueKey(PlatformNameRules.requireIdentifier(item.getExternalValueKey(), "externalValueKey"));
        } else if (!Boolean.TRUE.equals(item.getAllowExternalValue())) {
            item.setExternalValueKey(null);
        }
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            item.setTitle(moduleField.fieldTitle());
        }
    }

    private FieldQueryDefinition queryDefinition(ResolvedModuleMetadataField moduleField, FieldSpec fieldType) {
        if (fieldDefinitionCompiler == null) {
            return fieldType.queryDefinition();
        }
        return fieldDefinitionCompiler.compileQueryDefinition(moduleField.metadataFieldId(), moduleField.relationId());
    }

    private void appendChildren(Criteria criteria,
                                PlatformQueryGroupOperator operator,
                                List<PlatformQueryItem> children,
                                Map<String, List<PlatformQueryItem>> childrenByParent,
                                Map<String, ?> externalValues,
                                Set<String> visiting) {
        if (children == null || children.isEmpty()) {
            return;
        }
        boolean first = true;
        for (PlatformQueryItem child : children) {
            Criteria childCriteria = compileChild(child, childrenByParent, externalValues, visiting);
            if (childCriteria.isEmpty()) {
                continue;
            }
            if (first || operator == PlatformQueryGroupOperator.AND) {
                criteria.andGroup(childCriteria.getRoot());
            } else {
                criteria.orGroup(childCriteria.getRoot());
            }
            first = false;
        }
    }

    private Criteria compileChild(PlatformQueryItem item,
                                  Map<String, List<PlatformQueryItem>> childrenByParent,
                                  Map<String, ?> externalValues,
                                  Set<String> visiting) {
        if (!visiting.add(item.getId())) {
            throw new PlatformException("Query item cycle detected while compiling: " + item.getId());
        }
        Criteria criteria = Criteria.of();
        if (isGroup(item)) {
            appendChildren(criteria, item.getGroupOperator(), childrenByParent.get(item.getId()), childrenByParent,
                    externalValues, visiting);
            visiting.remove(item.getId());
            return criteria;
        }
        ResolvedModuleMetadataField moduleField = moduleFieldService.resolve(item.getModuleMetadataFieldId());
        FieldSpec fieldType = fieldTypeService.requireFieldType(moduleField.fieldSpecAlias());
        validateCurrentQueryDefinition(moduleField, fieldType, item);
        Object value = resolveQueryValue(item, externalValues);
        if (isEmptyValue(value) && !isNoValueOperator(item.getOperator())) {
            visiting.remove(item.getId());
            return criteria;
        }
        appendLeaf(criteria, moduleField.fieldName(), fieldType, item, value);
        visiting.remove(item.getId());
        return criteria;
    }

    private void validateCurrentQueryDefinition(ResolvedModuleMetadataField moduleField,
                                                FieldSpec fieldType,
                                                PlatformQueryItem item) {
        FieldQueryDefinition queryDefinition = queryDefinition(moduleField, fieldType);
        if (!queryDefinition.queryable()) {
            throw new PlatformException("Query item field is not queryable: " + moduleField.fieldName());
        }
        DynamicQueryOperator operator = item.getOperator() == null
                ? queryDefinition.defaultOperator()
                : item.getOperator();
        if (!queryDefinition.operators().contains(operator)) {
            throw new PlatformException("Query item operator is not allowed: "
                    + moduleField.fieldName() + "." + operator);
        }
        item.setOperator(operator);
    }

    private void validateReachableTree(List<PlatformQueryItem> items,
                                       Map<String, List<PlatformQueryItem>> childrenByParent) {
        Set<String> itemIds = new HashSet<>();
        for (PlatformQueryItem item : items) {
            itemIds.add(item.getId());
        }
        Set<String> reachable = new HashSet<>();
        collectReachable(TreeAbility.ROOT_ID, childrenByParent, itemIds, reachable, new HashSet<>());
        if (reachable.size() != itemIds.size()) {
            throw new PlatformException("Query template contains items outside root tree");
        }
    }

    private void collectReachable(String parentId,
                                  Map<String, List<PlatformQueryItem>> childrenByParent,
                                  Set<String> itemIds,
                                  Set<String> reachable,
                                  Set<String> visitingParents) {
        if (!visitingParents.add(parentId)) {
            throw new PlatformException("Query item cycle detected while validating: " + parentId);
        }
        for (PlatformQueryItem child : childrenByParent.getOrDefault(parentId, List.of())) {
            if (!itemIds.contains(child.getId())) {
                continue;
            }
            if (!reachable.add(child.getId())) {
                throw new PlatformException("Query item is reachable more than once: " + child.getId());
            }
            collectReachable(child.getId(), childrenByParent, itemIds, reachable, visitingParents);
        }
        visitingParents.remove(parentId);
    }

    private void appendLeaf(Criteria criteria,
                            String fieldName,
                            FieldSpec fieldType,
                            PlatformQueryItem item,
                            Object value) {
        DynamicQueryOperator operator = item.getOperator();
        switch (operator) {
            case EQ -> criteria.eq(fieldName, singleValue(operator, value));
            case NOT_EQUAL -> criteria.ne(fieldName, singleValue(operator, value));
            case LIKE -> criteria.like(fieldName, String.valueOf(singleValue(operator, value)));
            case IN -> criteria.in(fieldName, listValues(operator, value));
            case NOT_IN -> criteria.notIn(fieldName, listValues(operator, value));
            case BETWEEN -> {
                List<?> values = listValues(operator, value);
                if (values.size() != 2) {
                    throw new PlatformException("BETWEEN query item requires exactly two values: " + fieldName);
                }
                try {
                    if (DynamicTemporalRangeCriteriaSupport.appendInstantLocalDateRange(
                            criteria,
                            fieldName,
                            fieldType.getFieldType(),
                            values.get(0),
                            values.get(1),
                            item.getTimeZone(),
                            timeService,
                            BusinessTimeContext.empty()
                    )) {
                        return;
                    }
                } catch (RuntimeException ex) {
                    if (hasText(item.getTimeZone())) {
                        try {
                            PlatformTimeService.requireIanaZoneId(item.getTimeZone());
                        } catch (RuntimeException ignored) {
                            throw new PlatformException("Query item timeZone is invalid: " + item.getTimeZone(), ex);
                        }
                    }
                    throw new PlatformException("Query item date range is invalid: " + fieldName, ex);
                }
                criteria.between(fieldName, values.get(0), values.get(1));
            }
            case GT -> criteria.gt(fieldName, singleValue(operator, value));
            case GTE -> criteria.gte(fieldName, singleValue(operator, value));
            case LT -> criteria.lt(fieldName, singleValue(operator, value));
            case LTE -> criteria.lte(fieldName, singleValue(operator, value));
            case NULL -> criteria.isNull(fieldName);
            case NOT_NULL -> criteria.isNotNull(fieldName);
            case CONTAINS -> criteria.contains(fieldName, singleValue(operator, value));
            case CONTAINS_ANY -> criteria.containsAny(fieldName, listValues(operator, value));
            case CONTAINS_ALL -> criteria.containsAll(fieldName, listValues(operator, value));
            case EMPTY -> criteria.isEmpty(fieldName);
            case NOT_EMPTY -> criteria.isNotEmpty(fieldName);
        }
    }

    private Object resolveQueryValue(PlatformQueryItem item, Map<String, ?> externalValues) {
        if (Boolean.TRUE.equals(item.getAllowExternalValue()) && hasText(item.getExternalValueKey())
                && externalValues.containsKey(item.getExternalValueKey())) {
            return externalValues.get(item.getExternalValueKey());
        }
        return item.getDefaultValue();
    }

    private Object singleValue(DynamicQueryOperator operator, Object value) {
        List<?> values = listValues(operator, value);
        if (values.size() != 1) {
            throw new PlatformException(operator + " query item requires exactly one value");
        }
        return values.getFirst();
    }

    private List<?> listValues(DynamicQueryOperator operator, Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(v -> !isEmptyValue(v)).toList();
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object element = Array.get(value, index);
                if (!isEmptyValue(element)) {
                    values.add(element);
                }
            }
            return values;
        }
        if (value instanceof String text && (operator == DynamicQueryOperator.IN
                || operator == DynamicQueryOperator.NOT_IN
                || operator == DynamicQueryOperator.BETWEEN
                || operator == DynamicQueryOperator.CONTAINS_ANY
                || operator == DynamicQueryOperator.CONTAINS_ALL)) {
            return List.of(text.split(",")).stream()
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .toList();
        }
        return List.of(value);
    }

    private boolean isGroup(PlatformQueryItem item) {
        return item.getModuleMetadataFieldId() == null || item.getModuleMetadataFieldId().isBlank();
    }

    private String normalizeParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? TreeAbility.ROOT_ID : parentId;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    private boolean isNoValueOperator(DynamicQueryOperator operator) {
        return operator == DynamicQueryOperator.NULL
                || operator == DynamicQueryOperator.NOT_NULL
                || operator == DynamicQueryOperator.EMPTY
                || operator == DynamicQueryOperator.NOT_EMPTY;
    }

    private void requireDraftQueryTemplate(String queryTemplateId) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(queryTemplateId);
        if (Boolean.TRUE.equals(template.getPublished())) {
            throw new PlatformException("Published query template items cannot be edited; unpublish first: "
                    + template.getId());
        }
    }
}
