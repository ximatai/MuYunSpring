package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjectionReader;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceAffectDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source-neutral dynamic reference picker. Dynamic metadata owns the declared plan; target
 * records are read through {@link ReferenceAbility}, whether the target is dynamic or static.
 */
final class DynamicReferenceResolver {
    private final DynamicEntityService sourceService;
    private final ReferencePlan plan;
    private final ReferenceAbility<?> targetAbility;
    private final DynamicEntityService dynamicTargetService;
    private final List<EntityReferenceAffectDefinition> affects;

    DynamicReferenceResolver(DynamicEntityService sourceService,
                             ReferencePlan plan,
                             DynamicEntityService targetService) {
        this(sourceService, plan, targetService, List.of());
    }

    DynamicReferenceResolver(DynamicEntityService sourceService,
                             ReferencePlan plan,
                             DynamicEntityService targetService,
                             List<EntityReferenceAffectDefinition> affects) {
        this.sourceService = Objects.requireNonNull(sourceService, "sourceService must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.dynamicTargetService = Objects.requireNonNull(targetService, "targetService must not be null");
        this.targetAbility = targetService.referenceAbility();
        this.affects = affects == null ? List.of() : List.copyOf(affects);
    }

    DynamicReferenceResolver(DynamicEntityService sourceService,
                             ReferencePlan plan,
                             ReferenceAbility<?> targetAbility,
                             List<EntityReferenceAffectDefinition> affects) {
        this.sourceService = Objects.requireNonNull(sourceService, "sourceService must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.targetAbility = Objects.requireNonNull(targetAbility, "targetAbility must not be null");
        this.dynamicTargetService = null;
        this.affects = affects == null ? List.of() : List.copyOf(affects);
    }

    DynamicReferenceResolveResponse resolve(DynamicReferenceResolveRequest request) {
        DynamicReferenceResolveRequest effective = request == null
                ? DynamicReferenceResolveRequest.query(null)
                : request;
        sourceService.requireSameEntityAliasForReference(plan);
        return effective.mode() == DynamicReferenceResolveMode.TRANSLATE ? translate(effective) : query(effective);
    }

    private DynamicReferenceResolveResponse query(DynamicReferenceResolveRequest request) {
        if (dynamicTargetService != null) {
            PageResult<DynamicRecord> page = dynamicTargetService.pageQuery(queryCriteria(request), request.pageRequest());
            requireUniqueDynamicKeys(page.getRecords());
            Map<String, Map<String, Object>> selectionProjections = dynamicSelectionProjections(page.getRecords(), request.includeProjections());
            List<DynamicReferenceResolveItem> items = page.getRecords().stream()
                    .map(record -> dynamicItem(record, matchedBy(record, request.fuzzy(), request.matchMode()),
                            request.includeProjections(), selectionProjections.get(record.getId())))
                    .toList();
            return new DynamicReferenceResolveResponse(resolveQueryStatus(page.getTotal()), DynamicReferenceResolveMode.QUERY,
                    items, List.of(), request.pageRequest().getOffset(), request.pageRequest().getLimit(), page.getTotal());
        }
        PageResult<ReferenceOption> page = referenceOptions(queryCriteria(request), request.pageRequest());
        return new DynamicReferenceResolveResponse(
                resolveQueryStatus(page.getTotal()), DynamicReferenceResolveMode.QUERY,
                items(page.getRecords(), request.fuzzy(), request.matchMode(), request.includeProjections()), List.of(),
                request.pageRequest().getOffset(), request.pageRequest().getLimit(), page.getTotal());
    }

    private DynamicReferenceResolveResponse translate(DynamicReferenceResolveRequest request) {
        List<DynamicReferenceResolveResult> results = request.values().stream()
                .map(value -> translateOne(value, request)).toList();
        return new DynamicReferenceResolveResponse(resolveBatchStatus(results), DynamicReferenceResolveMode.TRANSLATE,
                List.of(), results, request.pageRequest().getOffset(), request.pageRequest().getLimit(), results.size());
    }

    private DynamicReferenceResolveResult translateOne(Object value, DynamicReferenceResolveRequest request) {
        if (request.matchMode() != DynamicReferenceMatchMode.AUTO) {
            return translateBy(value, request, request.matchMode());
        }
        DynamicReferenceResolveResult keyResult = translateBy(value, request, DynamicReferenceMatchMode.KEY);
        return keyResult.status() == DynamicReferenceResolveStatus.RESOLVED
                || keyResult.status() == DynamicReferenceResolveStatus.AMBIGUOUS
                ? keyResult
                : translateBy(value, request, DynamicReferenceMatchMode.LABEL);
    }

    private DynamicReferenceResolveResult translateBy(Object value,
                                                      DynamicReferenceResolveRequest request,
                                                      DynamicReferenceMatchMode matchMode) {
        Criteria criteria = baseCriteria(request.criteria());
        if (matchMode == DynamicReferenceMatchMode.KEY) {
            criteria.eq(plan.targetKeyField(), value);
        } else {
            criteria.eq(titleFieldName(), value);
        }
        if (dynamicTargetService != null) {
            PageResult<DynamicRecord> page = dynamicTargetService.pageQuery(criteria, request.pageRequest());
            requireUniqueDynamicKeys(page.getRecords());
            if (page.getTotal() == 0) {
                return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.NOT_FOUND, matchMode, null, List.of());
            }
            if (page.getTotal() == 1 && page.getRecords().size() == 1) {
                DynamicRecord record = page.getRecords().getFirst();
                return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.RESOLVED, matchMode,
                        dynamicItem(record, matchMode, request.includeProjections(), dynamicSelectionProjections(
                                List.of(record), request.includeProjections()).get(record.getId())), List.of());
            }
            Map<String, Map<String, Object>> selectionProjections = dynamicSelectionProjections(page.getRecords(),
                    request.includeProjections());
            return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.AMBIGUOUS, matchMode, null,
                    page.getRecords().stream().map(record -> dynamicItem(record, matchMode, request.includeProjections(),
                            selectionProjections.get(record.getId()))).toList());
        }
        PageResult<ReferenceOption> page = referenceOptions(criteria, request.pageRequest());
        if (page.getTotal() == 0) {
            return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.NOT_FOUND, matchMode, null, List.of());
        }
        List<DynamicReferenceResolveItem> candidates = items(page.getRecords(), String.valueOf(value), matchMode,
                request.includeProjections());
        if (page.getTotal() == 1 && candidates.size() == 1) {
            return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.RESOLVED, matchMode,
                    candidates.getFirst(), List.of());
        }
        return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.AMBIGUOUS, matchMode, null,
                candidates);
    }

    private Criteria queryCriteria(DynamicReferenceResolveRequest request) {
        Criteria criteria = baseCriteria(request.criteria());
        String fuzzy = request.fuzzy();
        if (fuzzy == null || fuzzy.isBlank()) return criteria;
        if (request.matchMode() == DynamicReferenceMatchMode.KEY) return criteria.eq(plan.targetKeyField(), fuzzy);
        if (request.matchMode() == DynamicReferenceMatchMode.LABEL) return criteria.like(titleFieldName(), fuzzy);
        return criteria.andGroup(group -> group
                .or(plan.targetKeyField(), net.ximatai.muyun.database.core.orm.CriteriaOperator.EQ, fuzzy)
                .or(titleFieldName(), net.ximatai.muyun.database.core.orm.CriteriaOperator.LIKE, fuzzy));
    }

    private Criteria baseCriteria(Criteria base) {
        Criteria criteria = Criteria.of();
        if (base != null && !base.isEmpty()) criteria.andGroup(base.getRoot());
        return criteria;
    }

    private List<DynamicReferenceResolveItem> items(List<ReferenceOption> options,
                                                    String fuzzy,
                                                    DynamicReferenceMatchMode requested,
                                                    boolean includeProjections) {
        if (options == null || options.isEmpty()) return List.of();
        List<String> ids = options.stream().map(ReferenceOption::id).toList();
        Map<String, Map<String, Object>> values = targetValues(ids, includeProjections);
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(options, includeProjections);
        return options.stream().map(option -> item(option, matchedBy(option, fuzzy, requested), includeProjections,
                values.get(option.id()), selectionProjections.get(option.recordId()))).toList();
    }

    private DynamicReferenceResolveItem item(ReferenceOption option,
                                             DynamicReferenceMatchMode matchedBy,
                                             boolean includeProjections,
                                             Map<String, Object> targetValues,
                                             Map<String, Object> selectionProjections) {
        return new DynamicReferenceResolveItem(option.id(), option.title(), matchedBy,
                projectionValues(targetValues, includeProjections, selectionProjections), affectPatch(targetValues));
    }

    private DynamicReferenceResolveItem dynamicItem(DynamicRecord record,
                                                    DynamicReferenceMatchMode matchedBy,
                                                    boolean includeProjections,
                                                    Map<String, Object> selectionProjections) {
        return new DynamicReferenceResolveItem(dynamicKey(record), dynamicLabel(record), matchedBy,
                dynamicProjectionValues(record, includeProjections, selectionProjections), dynamicAffectPatch(record));
    }

    private Map<String, Object> dynamicProjectionValues(DynamicRecord record,
                                                        boolean includeProjections,
                                                        Map<String, Object> selectionProjections) {
        if (!includeProjections || (plan.projections().isEmpty() && plan.selectionProjections().isEmpty())) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (ReferenceProjection projection : plan.projections()) {
            values.put(projection.outputField(), dynamicTargetService.maskProtectedValue(
                    projection.targetField(), record.getValue(projection.targetField()),
                    net.ximatai.muyun.spring.common.security.FieldOutputContext.REFERENCE));
        }
        if (selectionProjections != null) values.putAll(selectionProjections);
        return values;
    }

    private Map<String, Object> dynamicAffectPatch(DynamicRecord record) {
        if (affects.isEmpty()) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityReferenceAffectDefinition affect : affects) {
            values.put(affect.targetField(), dynamicTargetService.maskProtectedValue(
                    affect.referenceField(), record.getValue(affect.referenceField()),
                    net.ximatai.muyun.spring.common.security.FieldOutputContext.REFERENCE));
        }
        return values;
    }

    private Map<String, Map<String, Object>> targetValues(List<String> ids, boolean includeProjections) {
        if (ids.isEmpty()) return Map.of();
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
        if (includeProjections) {
            plan.projections().forEach(projection -> fieldNames.add(projection.targetField()));
        }
        affects.forEach(affect -> fieldNames.add(affect.referenceField()));
        if (fieldNames.isEmpty()) return Map.of();
        return plan.usesDefaultTargetFields()
                ? targetAbility.projections(ids, List.copyOf(fieldNames))
                : targetAbility.projections(plan, ids, List.copyOf(fieldNames));
    }

    private Map<String, Object> projectionValues(Map<String, Object> targetValues,
                                                  boolean includeProjections,
                                                  Map<String, Object> selectionProjections) {
        if (!includeProjections || (plan.projections().isEmpty() && plan.selectionProjections().isEmpty())) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (ReferenceProjection projection : plan.projections()) {
            values.put(projection.outputField(), targetValues == null ? null : targetValues.get(projection.targetField()));
        }
        if (selectionProjections != null) values.putAll(selectionProjections);
        return values;
    }

    private Map<String, Map<String, Object>> selectionProjections(List<ReferenceOption> options, boolean includeProjections) {
        if (!includeProjections || plan.selectionProjections().isEmpty() || options.isEmpty()) return Map.of();
        return ReferenceSelectionProjectionReader.read(plan.target(), options.stream().map(ReferenceOption::recordId).toList(), plan.selectionProjections(),
                sourceService.referenceTargetResolver());
    }

    private Map<String, Map<String, Object>> dynamicSelectionProjections(List<DynamicRecord> records,
                                                                           boolean includeProjections) {
        if (!includeProjections || plan.selectionProjections().isEmpty() || records == null || records.isEmpty()) {
            return Map.of();
        }
        return ReferenceSelectionProjectionReader.read(plan.target(), records.stream().map(DynamicRecord::getId).toList(),
                plan.selectionProjections(), dynamicTargetService.referenceTargetResolver());
    }

    private Map<String, Object> affectPatch(Map<String, Object> targetValues) {
        if (affects.isEmpty()) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityReferenceAffectDefinition affect : affects) {
            values.put(affect.targetField(), targetValues == null ? null : targetValues.get(affect.referenceField()));
        }
        return values;
    }

    private DynamicReferenceMatchMode matchedBy(ReferenceOption option,
                                                String fuzzy,
                                                DynamicReferenceMatchMode requested) {
        if (requested != DynamicReferenceMatchMode.AUTO || fuzzy == null || fuzzy.isBlank()) {
            return requested == DynamicReferenceMatchMode.AUTO ? DynamicReferenceMatchMode.LABEL : requested;
        }
        return Objects.equals(option.id(), fuzzy) ? DynamicReferenceMatchMode.KEY : DynamicReferenceMatchMode.LABEL;
    }

    private DynamicReferenceMatchMode matchedBy(DynamicRecord record,
                                                String fuzzy,
                                                DynamicReferenceMatchMode requested) {
        if (requested != DynamicReferenceMatchMode.AUTO || fuzzy == null || fuzzy.isBlank()) {
            return requested == DynamicReferenceMatchMode.AUTO ? DynamicReferenceMatchMode.LABEL : requested;
        }
        return Objects.equals(dynamicKey(record), fuzzy) ? DynamicReferenceMatchMode.KEY : DynamicReferenceMatchMode.LABEL;
    }

    private String dynamicKey(DynamicRecord record) {
        Object value = "id".equals(plan.targetKeyField()) ? record.getId() : record.getValue(plan.targetKeyField());
        return value == null ? null : String.valueOf(value);
    }

    private String dynamicLabel(DynamicRecord record) {
        if (plan.targetLabelField() == null) return dynamicTargetService.referenceTitle(record);
        Object value = dynamicTargetService.maskProtectedValue(plan.targetLabelField(),
                record.getValue(plan.targetLabelField()), net.ximatai.muyun.spring.common.security.FieldOutputContext.REFERENCE);
        return value == null ? null : String.valueOf(value);
    }

    private void requireUniqueDynamicKeys(List<DynamicRecord> records) {
        if (plan.usesDefaultTargetFields()) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (DynamicRecord record : records) {
            String key = dynamicKey(record);
            if (!values.add(key)) {
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("reference target key is not unique: "
                        + plan.target().qualifiedName() + "." + plan.targetKeyField() + "=" + key);
            }
        }
    }

    private DynamicReferenceResolveStatus resolveQueryStatus(long total) {
        return total == 0 ? DynamicReferenceResolveStatus.NOT_FOUND : DynamicReferenceResolveStatus.OK;
    }

    private DynamicReferenceResolveStatus resolveBatchStatus(List<DynamicReferenceResolveResult> results) {
        if (results.isEmpty()) return DynamicReferenceResolveStatus.NOT_FOUND;
        if (results.stream().allMatch(result -> result.status() == DynamicReferenceResolveStatus.RESOLVED)) {
            return DynamicReferenceResolveStatus.RESOLVED;
        }
        if (results.stream().anyMatch(result -> result.status() == DynamicReferenceResolveStatus.AMBIGUOUS)) {
            return DynamicReferenceResolveStatus.AMBIGUOUS;
        }
        return results.stream().noneMatch(result -> result.status() == DynamicReferenceResolveStatus.RESOLVED)
                ? DynamicReferenceResolveStatus.NOT_FOUND : DynamicReferenceResolveStatus.PARTIAL;
    }

    private String titleFieldName() {
        if (plan.targetLabelField() != null) return plan.targetLabelField();
        if (dynamicTargetService != null) return PlatformAbilityFields.TITLE_FIELD;
        return TitleFieldResolver.resolveFieldName(targetAbility.modelClass()).orElse(PlatformAbilityFields.TITLE_FIELD);
    }

    private PageResult<ReferenceOption> referenceOptions(Criteria criteria, PageRequest pageRequest) {
        return plan.usesDefaultTargetFields()
                ? targetAbility.referenceOptions(criteria, pageRequest)
                : targetAbility.referenceOptions(plan, criteria, pageRequest);
    }
}
