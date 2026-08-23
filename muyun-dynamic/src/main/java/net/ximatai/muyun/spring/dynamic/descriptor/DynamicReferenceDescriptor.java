package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;

import java.util.List;
import java.util.Set;

public record DynamicReferenceDescriptor(
        String sourceEntityAlias,
        String sourceField,
        String targetModuleAlias,
        String targetEntityAlias,
        ReferenceCardinality cardinality,
        List<DynamicReferenceProjectionDescriptor> projections,
        String keyField,
        String labelField,
        String generateRuleId,
        String queryTemplateId,
        Set<String> plusFields,
        List<DynamicReferenceFilterDescriptor> filters,
        List<DynamicReferenceAffectDescriptor> affects,
        ReferenceIntegrityPolicy integrity,
        ReferenceTenantScope tenantScope
) {
    public DynamicReferenceDescriptor(String sourceEntityAlias,
                                      String sourceField,
                                      String targetModuleAlias,
                                      String targetEntityAlias,
                                      ReferenceCardinality cardinality,
                                      List<DynamicReferenceProjectionDescriptor> projections) {
        this(sourceEntityAlias, sourceField, targetModuleAlias, targetEntityAlias, cardinality,
                projections, null, null, null, null, Set.of(), List.of(), List.of(),
                ReferenceIntegrityPolicy.DEFAULT, ReferenceTenantScope.SAME_TENANT);
    }

    public DynamicReferenceDescriptor(String sourceEntityAlias,
                                      String sourceField,
                                      String targetModuleAlias,
                                      String targetEntityAlias,
                                      ReferenceCardinality cardinality,
                                      List<DynamicReferenceProjectionDescriptor> projections,
                                      String keyField,
                                      String labelField,
                                      String generateRuleId,
                                      String queryTemplateId,
                                      Set<String> plusFields,
                                      List<DynamicReferenceFilterDescriptor> filters,
                                      List<DynamicReferenceAffectDescriptor> affects) {
        this(sourceEntityAlias, sourceField, targetModuleAlias, targetEntityAlias, cardinality,
                projections, keyField, labelField, generateRuleId, queryTemplateId,
                plusFields, filters, affects, ReferenceIntegrityPolicy.DEFAULT, ReferenceTenantScope.SAME_TENANT);
    }

    /** Compatibility constructor for descriptors issued before tenant scope was explicit. */
    public DynamicReferenceDescriptor(String sourceEntityAlias,
                                      String sourceField,
                                      String targetModuleAlias,
                                      String targetEntityAlias,
                                      ReferenceCardinality cardinality,
                                      List<DynamicReferenceProjectionDescriptor> projections,
                                      String keyField,
                                      String labelField,
                                      String generateRuleId,
                                      String queryTemplateId,
                                      Set<String> plusFields,
                                      List<DynamicReferenceFilterDescriptor> filters,
                                      List<DynamicReferenceAffectDescriptor> affects,
                                      ReferenceIntegrityPolicy integrity) {
        this(sourceEntityAlias, sourceField, targetModuleAlias, targetEntityAlias, cardinality,
                projections, keyField, labelField, generateRuleId, queryTemplateId,
                plusFields, filters, affects, integrity, ReferenceTenantScope.SAME_TENANT);
    }

    public DynamicReferenceDescriptor {
        projections = projections == null ? List.of() : List.copyOf(projections);
        plusFields = plusFields == null ? Set.of() : Set.copyOf(plusFields);
        filters = filters == null ? List.of() : List.copyOf(filters);
        affects = affects == null ? List.of() : List.copyOf(affects);
        integrity = integrity == null ? ReferenceIntegrityPolicy.DEFAULT : integrity;
        tenantScope = tenantScope == null ? ReferenceTenantScope.SAME_TENANT : tenantScope;
    }

    public static DynamicReferenceDescriptor from(EntityReferenceDefinition reference) {
        ReferenceTarget target = reference.target();
        return new DynamicReferenceDescriptor(
                reference.sourceEntityAlias(),
                reference.sourceField(),
                target.moduleAlias(),
                target.entityAlias(),
                reference.cardinality(),
                reference.projections().stream().map(DynamicReferenceProjectionDescriptor::from).toList(),
                reference.keyField(),
                reference.labelField(),
                reference.generateRuleId(),
                reference.queryTemplateId(),
                reference.plusFields(),
                reference.filters().stream().map(DynamicReferenceFilterDescriptor::from).toList(),
                reference.affects().stream().map(DynamicReferenceAffectDescriptor::from).toList(),
                reference.integrity(),
                reference.tenantScope()
        );
    }

    /** The equality subset of dynamic interaction filters is the shared candidate-dependency contract. */
    public List<ReferenceCandidateDependency> candidateDependencies() {
        return filters.stream()
                .filter(filter -> filter.operator() == null || filter.operator() == DynamicQueryOperator.EQ)
                .map(filter -> new ReferenceCandidateDependency(filter.formField(), filter.referenceField(), true))
                .toList();
    }

}
