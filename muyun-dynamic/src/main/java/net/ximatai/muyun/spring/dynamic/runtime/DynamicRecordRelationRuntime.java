package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationItem;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceFilterDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewQueryMappingGroupOperator;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewQueryMappingSourceType;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewRootQueryMapping;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-side relation boundary for dynamic records.
 *
 * <p>It owns declared references, association-view projection and relation topology.  Generic
 * querying, mutation and action orchestration deliberately remain in their dedicated runtimes;
 * the record-service facade only preserves the established public entry points.</p>
 */
final class DynamicRecordRelationRuntime {
    private final DynamicRecordService records;

    DynamicRecordRelationRuntime(DynamicRecordService records) {
        this.records = Objects.requireNonNull(records, "records must not be null");
    }

    List<DynamicAssociationViewDescriptor> associationViews(String moduleAlias) {
        return records.describe(moduleAlias).associationViews();
    }

    List<DynamicAssociationViewDescriptor> associationViews(String moduleAlias, String entityAlias) {
        return records.entityDescriptor(moduleAlias, entityAlias).associationViews();
    }

    DynamicAssociationViewDescriptor associationView(String moduleAlias, String entityAlias, String viewCode) {
        return associationViews(moduleAlias, entityAlias).stream()
                .filter(view -> view.code().equals(viewCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic association view: "
                        + moduleAlias + "." + entityAlias + "." + viewCode));
    }

    PageResult<DynamicRecord> associationViewPage(String moduleAlias, String entityAlias, String sourceRecordId,
                                                  String viewCode, Criteria criteria, PageRequest pageRequest,
                                                  Sort... sorts) {
        DynamicAssociationViewDescriptor view = associationView(moduleAlias, entityAlias, viewCode);
        if (!view.queryable()) {
            throw new PlatformException("dynamic association view is not queryable: " + moduleAlias + "." + viewCode);
        }
        DynamicRecord source = requireAssociationSource(moduleAlias, entityAlias, sourceRecordId);
        Criteria associationCriteria = associationCriteria(moduleAlias, entityAlias, source, view);
        return records.page(view.targetModuleAlias(), view.targetEntityAlias(),
                associationTargetCriteria(source, view, associationCriteria, criteria), pageRequest, sorts);
    }

    DynamicAssociationRelationOverview associationRelationOverview(String moduleAlias) {
        DynamicModuleDescriptor descriptor = records.describe(moduleAlias);
        Map<String, String> viewByRelation = new LinkedHashMap<>();
        Map<String, String> viewByReference = new LinkedHashMap<>();
        for (DynamicAssociationViewDescriptor view : descriptor.associationViews()) {
            if (view.relationCode() != null && !view.relationCode().isBlank()) {
                viewByRelation.put(view.sourceEntityAlias() + "." + view.relationCode(), view.code());
            }
            if (view.referenceField() != null && !view.referenceField().isBlank()) {
                viewByReference.put(view.sourceEntityAlias() + "." + view.referenceField(), view.code());
            }
        }
        List<DynamicAssociationRelationItem> downstream = new ArrayList<>();
        List<DynamicAssociationRelationItem> upstream = new ArrayList<>();
        for (DynamicRelationDescriptor relation : descriptor.relations()) {
            String viewCode = viewByRelation.get(relation.parentEntityAlias() + "." + relation.code());
            downstream.add(new DynamicAssociationRelationItem("RELATION", relation.code(), moduleAlias,
                    relation.parentEntityAlias(), moduleAlias, relation.childEntityAlias(), viewCode));
            upstream.add(new DynamicAssociationRelationItem("RELATION", relation.code(), moduleAlias,
                    relation.childEntityAlias(), moduleAlias, relation.parentEntityAlias(), viewCode));
        }
        for (DynamicReferenceDescriptor reference : descriptor.references()) {
            String viewCode = viewByReference.get(reference.sourceEntityAlias() + "." + reference.sourceField());
            downstream.add(new DynamicAssociationRelationItem("REFERENCE", reference.sourceField(), moduleAlias,
                    reference.sourceEntityAlias(), reference.targetModuleAlias(), reference.targetEntityAlias(), viewCode));
            if (moduleAlias.equals(reference.targetModuleAlias())) {
                upstream.add(new DynamicAssociationRelationItem("REFERENCE", reference.sourceField(), moduleAlias,
                        reference.targetEntityAlias(), moduleAlias, reference.sourceEntityAlias(), viewCode));
            }
        }
        return new DynamicAssociationRelationOverview(moduleAlias, upstream, downstream);
    }

    DynamicAssociationViewDiagnosis diagnoseAssociationView(String moduleAlias, String entityAlias,
                                                             String sourceRecordId, String viewCode,
                                                             Criteria criteria) {
        DynamicAssociationViewDescriptor view = associationView(moduleAlias, entityAlias, viewCode);
        if (!view.queryable()) {
            throw new PlatformException("dynamic association view is not queryable: " + moduleAlias + "." + viewCode);
        }
        DynamicRecord source = requireAssociationSource(moduleAlias, entityAlias, sourceRecordId);
        Criteria associationCriteria = associationCriteria(moduleAlias, entityAlias, source, view);
        Criteria targetCriteria = associationTargetCriteria(source, view, associationCriteria, criteria);
        long targetCount = records.count(view.targetModuleAlias(), view.targetEntityAlias(), targetCriteria);
        DynamicAssociationViewDiagnosisStatus status = diagnosisStatus(view, targetCount);
        return new DynamicAssociationViewDiagnosis(view, associationCriteria, criteria == null ? Criteria.of() : criteria,
                targetCriteria, targetCount, status, diagnosisMessage(status, targetCount));
    }

    List<DynamicRelationDescriptor> relations(String moduleAlias) {
        return records.describe(moduleAlias).relations();
    }

    List<DynamicReferenceDescriptor> references(String moduleAlias) {
        return records.describe(moduleAlias).references();
    }

    List<DynamicReferenceDescriptor> references(String moduleAlias, String entityAlias) {
        return references(moduleAlias).stream()
                .filter(reference -> reference.sourceEntityAlias().equals(entityAlias))
                .toList();
    }

    DynamicReferenceDescriptor reference(String moduleAlias, String entityAlias, String sourceField) {
        return references(moduleAlias, entityAlias).stream()
                .filter(reference -> reference.sourceField().equals(sourceField))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic reference: " + moduleAlias + "."
                        + entityAlias + "." + sourceField));
    }

    DynamicReferenceResolveResponse resolveReference(String moduleAlias, String entityAlias, String sourceField,
                                                      DynamicReferenceResolveRequest request) {
        DynamicReferenceDescriptor reference = reference(moduleAlias, entityAlias, sourceField);
        DynamicReferenceResolveRequest normalized = request == null
                ? DynamicReferenceResolveRequest.query(null) : request;
        Criteria criteria = referenceCriteria(normalized.criteria(), reference, normalized.formValues());
        if (!records.hasRegisteredDynamicEntity(reference.targetModuleAlias(), reference.targetEntityAlias())) {
            // Static targets execute their own REFERENCE data-scope policy through ReferenceAbility.
            // Do not force them through the dynamic runtime only because the source is metadata-driven.
            return records.entityService(moduleAlias, entityAlias)
                    .resolveReference(sourceField, normalized.withCriteria(criteria));
        }
        DataScopeCriteriaResult scope = records.readScope(reference.targetModuleAlias(), PlatformAction.REFERENCE, criteria);
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .resolveReference(sourceField, normalized.withCriteria(scope.criteria())));
    }

    String title(String moduleAlias, String entityAlias, String id) {
        records.requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.VIEW, Criteria.of().eq("id", id));
        if (!records.recordVisible(moduleAlias, entityAlias, scope, id)) return null;
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias).title(id));
    }

    Map<String, String> titles(String moduleAlias, String entityAlias, Collection<String> ids) {
        records.requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.VIEW, records.idsCriteria(ids));
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .titles(records.visibleRecordIds(moduleAlias, entityAlias, scope, ids)));
    }

    Map<String, Map<String, Object>> projections(String moduleAlias, String entityAlias, Collection<String> ids,
                                                  Collection<String> fieldNames) {
        records.requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE, records.idsCriteria(ids));
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .projections(records.visibleRecordIds(moduleAlias, entityAlias, scope, ids), fieldNames));
    }

    PageResult<ReferenceOption> referenceOptions(String moduleAlias, String entityAlias, Criteria criteria,
                                                 PageRequest pageRequest) {
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE, criteria);
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .referenceOptions(scope.criteria(), pageRequest));
    }

    Map<String, String> referenceLabels(String moduleAlias, String entityAlias, ReferencePlan plan,
                                        Collection<String> values) {
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE,
                referenceKeyCriteria(plan, values));
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .referenceLabels(plan, values, scope.criteria()));
    }

    Map<String, String> referenceRecordIds(String moduleAlias, String entityAlias, ReferencePlan plan,
                                            Collection<String> values) {
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE,
                referenceKeyCriteria(plan, values));
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .referenceRecordIds(plan, values, scope.criteria()));
    }

    Map<String, Map<String, Object>> projections(String moduleAlias, String entityAlias, ReferencePlan plan,
                                                  Collection<String> values, Collection<String> fieldNames) {
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE,
                referenceKeyCriteria(plan, values));
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .projections(plan, values, fieldNames, scope.criteria()));
    }

    PageResult<ReferenceOption> referenceOptions(String moduleAlias, String entityAlias, ReferencePlan plan,
                                                 Criteria criteria, PageRequest pageRequest) {
        DataScopeCriteriaResult scope = records.readScope(moduleAlias, PlatformAction.REFERENCE, criteria);
        return records.withTenantScope(scope, () -> records.entityService(moduleAlias, entityAlias)
                .referenceOptions(plan, scope.criteria(), pageRequest));
    }

    private Criteria referenceKeyCriteria(ReferencePlan plan, Collection<String> values) {
        List<String> keys = values == null ? List.of() : values.stream().filter(Objects::nonNull)
                .map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        return keys.isEmpty() ? falseCriteria() : Criteria.of().in(plan.targetKeyField(), keys);
    }

    private DynamicRecord requireAssociationSource(String moduleAlias, String entityAlias, String sourceRecordId) {
        DynamicRecord source = records.select(moduleAlias, entityAlias, sourceRecordId);
        if (source == null) {
            throw new PlatformException("dynamic association source record does not exist: " + sourceRecordId);
        }
        return source;
    }

    private Criteria associationCriteria(String moduleAlias, String entityAlias, DynamicRecord source,
                                         DynamicAssociationViewDescriptor view) {
        if (view.relationCode() != null && !view.relationCode().isBlank()) {
            DynamicRelationDescriptor relation = relations(moduleAlias).stream()
                    .filter(item -> item.code().equals(view.relationCode()) && item.parentEntityAlias().equals(entityAlias)
                            && item.childEntityAlias().equals(view.targetEntityAlias()))
                    .findFirst().orElseThrow(() -> new ModuleDefinitionException("unknown dynamic association relation: "
                            + moduleAlias + "." + view.code()));
            return Criteria.of().eq(relation.childForeignKeyField(), source.getId());
        }
        DynamicReferenceDescriptor reference = reference(moduleAlias, entityAlias, view.referenceField());
        String keyField = reference.keyField() == null || reference.keyField().isBlank() ? "id" : reference.keyField();
        Object value = source.getValue(reference.sourceField());
        if (value == null || (value instanceof String text && text.isBlank())) return falseCriteria();
        if (value instanceof Collection<?> collection) {
            List<?> values = collection.stream().filter(Objects::nonNull).filter(item -> !String.valueOf(item).isBlank()).toList();
            return values.isEmpty() ? falseCriteria() : Criteria.of().in(keyField, values);
        }
        return Criteria.of().eq(keyField, value);
    }

    private Criteria associationTargetCriteria(DynamicRecord source, DynamicAssociationViewDescriptor view,
                                               Criteria associationCriteria, Criteria requestCriteria) {
        return andCriteria(andCriteria(associationCriteria, rootQueryMappingCriteria(source, view)), requestCriteria);
    }

    private Criteria rootQueryMappingCriteria(DynamicRecord source, DynamicAssociationViewDescriptor view) {
        AssociationViewRootQueryMapping mapping = view.rootQueryMapping();
        return mapping == null ? Criteria.of() : mappingCriteria(source, view.targetModuleAlias(), view.targetEntityAlias(), mapping);
    }

    private Criteria mappingCriteria(DynamicRecord source, String targetModuleAlias, String targetEntityAlias,
                                     AssociationViewRootQueryMapping mapping) {
        if (mapping.leaf()) {
            Object value = mappingValue(source, mapping);
            if (value == null && mapping.operator() != DynamicQueryOperator.NULL && mapping.operator() != DynamicQueryOperator.NOT_NULL) {
                return falseCriteria();
            }
            return records.queryCriteria(targetModuleAlias, targetEntityAlias,
                    List.of(new DynamicQueryCondition(mapping.targetField(), mapping.operator(), mappingValues(mapping, value))));
        }
        Criteria criteria = Criteria.of();
        for (AssociationViewRootQueryMapping child : mapping.children()) {
            Criteria childCriteria = mappingCriteria(source, targetModuleAlias, targetEntityAlias, child);
            if (childCriteria.isEmpty()) continue;
            if (mapping.groupOperator() == AssociationViewQueryMappingGroupOperator.OR) criteria.orGroup(childCriteria.getRoot());
            else criteria.andGroup(childCriteria.getRoot());
        }
        return criteria;
    }

    private List<?> mappingValues(AssociationViewRootQueryMapping mapping, Object value) {
        return switch (mapping.operator()) {
            case NULL, NOT_NULL -> List.of();
            case IN, NOT_IN, BETWEEN -> value instanceof Collection<?> collection ? List.copyOf(collection) : List.of(value);
            default -> List.of(value);
        };
    }

    private Object mappingValue(DynamicRecord source, AssociationViewRootQueryMapping mapping) {
        AssociationViewQueryMappingSourceType type = mapping.sourceType();
        if (type == null) throw new ModuleDefinitionException("association rootQueryMapping source type is required");
        return switch (type) {
            case SOURCE_FIELD -> source.getValue(mapping.sourceField());
            case SYSTEM_VARIABLE -> systemVariableValue(source, mapping.systemVariable());
            case CONSTANT -> mapping.constantValue();
        };
    }

    private Object systemVariableValue(DynamicRecord source, String systemVariable) {
        if (systemVariable == null || systemVariable.isBlank()) {
            throw new ModuleDefinitionException("association rootQueryMapping system variable is required");
        }
        return switch (systemVariable.trim()) {
            case "source.id", "sourceId" -> source.getId();
            default -> throw new ModuleDefinitionException("unsupported association rootQueryMapping system variable: " + systemVariable);
        };
    }

    private DynamicAssociationViewDiagnosisStatus diagnosisStatus(DynamicAssociationViewDescriptor view, long targetCount) {
        if (view.viewType() != EntityViewType.FORM) return targetCount == 0
                ? DynamicAssociationViewDiagnosisStatus.EMPTY : DynamicAssociationViewDiagnosisStatus.OK;
        if (targetCount == 0) return DynamicAssociationViewDiagnosisStatus.FORM_NOT_FOUND;
        return targetCount > 1 ? DynamicAssociationViewDiagnosisStatus.FORM_NOT_UNIQUE : DynamicAssociationViewDiagnosisStatus.OK;
    }

    private String diagnosisMessage(DynamicAssociationViewDiagnosisStatus status, long targetCount) {
        return switch (status) {
            case OK -> "association view target matched";
            case EMPTY -> "association view target is empty";
            case FORM_NOT_FOUND -> "association view FORM target not found";
            case FORM_NOT_UNIQUE -> "association view FORM target must be unique, but matched " + targetCount;
        };
    }

    private Criteria andCriteria(Criteria left, Criteria right) {
        if (left == null || left.isEmpty()) return right == null ? Criteria.of() : right;
        if (right == null || right.isEmpty()) return left;
        Criteria criteria = Criteria.of();
        criteria.andGroup(left.getRoot());
        criteria.andGroup(right.getRoot());
        return criteria;
    }

    private Criteria falseCriteria() {
        return Criteria.of().raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", Map.of()));
    }

    private Criteria referenceCriteria(Criteria base, DynamicReferenceDescriptor reference, Map<String, Object> formValues) {
        Criteria criteria = Criteria.of();
        if (base != null && !base.isEmpty()) criteria.andGroup(base.getRoot());
        if (reference.filters().isEmpty() || formValues == null || formValues.isEmpty()) return criteria;
        for (DynamicReferenceFilterDescriptor filter : reference.filters()) {
            Object value = formValues.get(filter.formField());
            if (value == null || (value instanceof String text && text.isBlank())) continue;
            appendReferenceFilter(criteria, filter, value);
        }
        return criteria;
    }

    private void appendReferenceFilter(Criteria criteria, DynamicReferenceFilterDescriptor filter, Object value) {
        String fieldName = filter.referenceField();
        DynamicQueryOperator operator = filter.operator() == null ? DynamicQueryOperator.EQ : filter.operator();
        switch (operator) {
            case EQ -> criteria.eq(fieldName, value);
            case LIKE -> criteria.like(fieldName, String.valueOf(value));
            case IN -> criteria.in(fieldName, referenceFilterValues(value));
            case BETWEEN -> {
                List<?> values = referenceFilterValues(value);
                if (values.size() != 2) throw new ModuleDefinitionException("reference filter BETWEEN requires exactly two values: "
                        + filter.formField() + " -> " + fieldName);
                criteria.between(fieldName, values.get(0), values.get(1));
            }
            case GT -> criteria.gt(fieldName, value);
            case GTE -> criteria.gte(fieldName, value);
            case LT -> criteria.lt(fieldName, value);
            case LTE -> criteria.lte(fieldName, value);
        }
    }

    private List<?> referenceFilterValues(Object value) {
        if (value instanceof Collection<?> collection) return List.copyOf(collection);
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) values.add(java.lang.reflect.Array.get(value, index));
            return values;
        }
        return List.of(value);
    }
}
