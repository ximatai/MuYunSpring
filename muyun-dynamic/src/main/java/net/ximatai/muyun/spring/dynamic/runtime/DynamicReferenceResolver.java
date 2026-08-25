package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceAffectDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DynamicReferenceResolver {
    private final DynamicEntityService sourceService;
    private final ReferencePlan plan;
    private final DynamicEntityService targetService;
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
        this.targetService = Objects.requireNonNull(targetService, "targetService must not be null");
        this.affects = affects == null ? List.of() : List.copyOf(affects);
    }

    DynamicReferenceResolveResponse resolve(DynamicReferenceResolveRequest request) {
        DynamicReferenceResolveRequest effective = request == null
                ? DynamicReferenceResolveRequest.query(null)
                : request;
        sourceService.requireSameEntityAliasForReference(plan);
        if (effective.mode() == DynamicReferenceResolveMode.TRANSLATE) {
            return translate(effective);
        }
        return query(effective);
    }

    private DynamicReferenceResolveResponse query(DynamicReferenceResolveRequest request) {
        Criteria criteria = queryCriteria(request);
        PageResult<DynamicRecord> page = targetService.pageQuery(criteria, request.pageRequest());
        List<DynamicReferenceResolveItem> items = page.getRecords().stream()
                .map(record -> item(record, matchedBy(record, request.fuzzy(), request.matchMode()), request.includeProjections()))
                .toList();
        return new DynamicReferenceResolveResponse(
                resolveQueryStatus(page.getTotal()),
                DynamicReferenceResolveMode.QUERY,
                items,
                List.of(),
                request.pageRequest().getOffset(),
                request.pageRequest().getLimit(),
                page.getTotal()
        );
    }

    private DynamicReferenceResolveResponse translate(DynamicReferenceResolveRequest request) {
        List<DynamicReferenceResolveResult> results = request.values().stream()
                .map(value -> translateOne(value, request))
                .toList();
        return new DynamicReferenceResolveResponse(
                resolveBatchStatus(results),
                DynamicReferenceResolveMode.TRANSLATE,
                List.of(),
                results,
                request.pageRequest().getOffset(),
                request.pageRequest().getLimit(),
                results.size()
        );
    }

    private DynamicReferenceResolveResult translateOne(Object value, DynamicReferenceResolveRequest request) {
        DynamicReferenceMatchMode matchMode = request.matchMode();
        if (matchMode == DynamicReferenceMatchMode.AUTO) {
            DynamicReferenceResolveResult keyResult = translateBy(value, request, DynamicReferenceMatchMode.KEY);
            if (keyResult.status() == DynamicReferenceResolveStatus.RESOLVED) {
                return keyResult;
            }
            if (keyResult.status() == DynamicReferenceResolveStatus.AMBIGUOUS) {
                return keyResult;
            }
            DynamicReferenceResolveResult labelResult = translateBy(value, request, DynamicReferenceMatchMode.LABEL);
            if (keyResult.status() == DynamicReferenceResolveStatus.NOT_FOUND) {
                return labelResult;
            }
            if (labelResult.status() == DynamicReferenceResolveStatus.NOT_FOUND) {
                return keyResult;
            }
            return labelResult;
        }
        return translateBy(value, request, matchMode);
    }

    private DynamicReferenceResolveResult translateBy(Object value,
                                                     DynamicReferenceResolveRequest request,
                                                     DynamicReferenceMatchMode matchMode) {
        Criteria criteria = baseCriteria(request.criteria());
        if (matchMode == DynamicReferenceMatchMode.KEY) {
            criteria.eq(StandardEntitySchema.ID_FIELD, value);
        } else {
            criteria.eq(titleFieldName(), value);
        }
        PageResult<DynamicRecord> page = targetService.pageQuery(criteria, request.pageRequest());
        if (page.getTotal() == 0) {
            return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.NOT_FOUND, matchMode, null, List.of());
        }
        if (page.getTotal() == 1 && page.getRecords().size() == 1) {
            return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.RESOLVED, matchMode,
                    item(page.getRecords().getFirst(), matchMode, request.includeProjections()), List.of());
        }
        return new DynamicReferenceResolveResult(value, DynamicReferenceResolveStatus.AMBIGUOUS, matchMode, null,
                page.getRecords().stream()
                        .map(record -> item(record, matchMode, request.includeProjections()))
                        .toList());
    }

    private Criteria queryCriteria(DynamicReferenceResolveRequest request) {
        Criteria criteria = baseCriteria(request.criteria());
        String fuzzy = request.fuzzy();
        if (fuzzy == null || fuzzy.isBlank()) {
            return criteria;
        }
        if (request.matchMode() == DynamicReferenceMatchMode.KEY) {
            return criteria.eq(StandardEntitySchema.ID_FIELD, fuzzy);
        }
        if (request.matchMode() == DynamicReferenceMatchMode.LABEL) {
            return criteria.like(titleFieldName(), fuzzy);
        }
        return criteria.andGroup(group -> group
                .or(StandardEntitySchema.ID_FIELD, net.ximatai.muyun.database.core.orm.CriteriaOperator.EQ, fuzzy)
                .or(titleFieldName(), net.ximatai.muyun.database.core.orm.CriteriaOperator.LIKE, fuzzy));
    }

    private Criteria baseCriteria(Criteria base) {
        Criteria criteria = Criteria.of();
        if (base != null && !base.isEmpty()) {
            criteria.andGroup(base.getRoot());
        }
        return criteria;
    }

    private DynamicReferenceResolveItem item(DynamicRecord record,
                                             DynamicReferenceMatchMode matchedBy,
                                             boolean includeProjections) {
        return new DynamicReferenceResolveItem(
                record.getId(),
                targetService.referenceTitle(record),
                matchedBy,
                projectionValues(record, includeProjections),
                affectPatch(record)
        );
    }

    private Map<String, Object> projectionValues(DynamicRecord record, boolean includeProjections) {
        if (!includeProjections || (plan.projections().isEmpty() && plan.selectionProjections().isEmpty())) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (ReferenceProjection projection : plan.projections()) {
            values.put(projection.outputField(), targetService.maskProtectedValue(
                    projection.targetField(),
                    record.getValue(projection.targetField()),
                    FieldOutputContext.REFERENCE
            ));
        }
        for (ReferenceSelectionProjection projection : plan.selectionProjections()) {
            values.put(projection.key(), selectionProjectionValue(record, projection));
        }
        return values;
    }

    private Object selectionProjectionValue(DynamicRecord source, ReferenceSelectionProjection projection) {
        DynamicEntityService current = targetService;
        DynamicRecord record = source;
        List<String> path = projection.path();
        for (String field : path.subList(0, path.size() - 1)) {
            ReferencePlan hop = current.referencePlan(field);
            if (hop.cardinality() != ReferenceCardinality.ONE) {
                throw new IllegalArgumentException("selection projection hop requires cardinality ONE: " + field);
            }
            Object id = record.getValue(field);
            if (id == null) return null;
            current = current.referenceService(hop.target());
            record = current.activeRaw(String.valueOf(id));
            if (record == null) return null;
        }
        String terminal = projection.targetField();
        return current.maskProtectedValue(terminal, record.getValue(terminal), FieldOutputContext.REFERENCE);
    }

    private Map<String, Object> affectPatch(DynamicRecord record) {
        if (affects.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityReferenceAffectDefinition affect : affects) {
            values.put(affect.targetField(), targetService.maskProtectedValue(
                    affect.referenceField(),
                    record.getValue(affect.referenceField()),
                    FieldOutputContext.REFERENCE
            ));
        }
        return values;
    }

    private DynamicReferenceMatchMode matchedBy(DynamicRecord record, String fuzzy, DynamicReferenceMatchMode requested) {
        if (requested != DynamicReferenceMatchMode.AUTO || fuzzy == null || fuzzy.isBlank()) {
            return requested == DynamicReferenceMatchMode.AUTO ? DynamicReferenceMatchMode.LABEL : requested;
        }
        return Objects.equals(record.getId(), fuzzy) ? DynamicReferenceMatchMode.KEY : DynamicReferenceMatchMode.LABEL;
    }

    private DynamicReferenceResolveStatus resolveQueryStatus(long total) {
        return total == 0 ? DynamicReferenceResolveStatus.NOT_FOUND : DynamicReferenceResolveStatus.OK;
    }

    private DynamicReferenceResolveStatus resolveBatchStatus(List<DynamicReferenceResolveResult> results) {
        if (results.isEmpty()) {
            return DynamicReferenceResolveStatus.NOT_FOUND;
        }
        boolean allResolved = results.stream().allMatch(result -> result.status() == DynamicReferenceResolveStatus.RESOLVED);
        if (allResolved) {
            return DynamicReferenceResolveStatus.RESOLVED;
        }
        boolean anyAmbiguous = results.stream().anyMatch(result -> result.status() == DynamicReferenceResolveStatus.AMBIGUOUS);
        if (anyAmbiguous) {
            return DynamicReferenceResolveStatus.AMBIGUOUS;
        }
        boolean noneResolved = results.stream().noneMatch(result -> result.status() == DynamicReferenceResolveStatus.RESOLVED);
        return noneResolved ? DynamicReferenceResolveStatus.NOT_FOUND : DynamicReferenceResolveStatus.PARTIAL;
    }

    private String titleFieldName() {
        return net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.TITLE_FIELD;
    }
}
