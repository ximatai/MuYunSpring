package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Shared read-side projection policy for declared relation columns. */
final class RelationReadProjectionSupport {
    private RelationReadProjectionSupport() {
    }

    /**
     * Reference labels are presentation companions of declared relation columns, not additional
     * business columns. Keeping them in the same projection lets every relation reader preserve
     * the narrow response contract while still rendering stable titles.
     */
    static List<String> outputFields(CrudAbility<?> childService, Collection<String> declaredFields) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(declaredFields);
        Class<?> modelClass = childService.modelClass();
        if (modelClass == null) return List.copyOf(fields);
        StaticReferenceResolver.plans(modelClass).forEach(plan -> {
            if (fields.contains(plan.sourceField())) {
                plan.projections().forEach(projection -> fields.add(projection.outputField()));
            }
        });
        StaticReferenceResolver.loadPaths(modelClass).forEach(path -> {
            if (fields.contains(path.sourceField())) {
                fields.add(path.outputField());
            }
        });
        return List.copyOf(fields);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<Map<String, Object>> project(CrudAbility<?> childService, String targetModuleAlias,
                                             String projectionCode, List<?> records, List<String> outputFields) {
        RecordReadProjection projection = new RecordReadProjection(targetModuleAlias, projectionCode,
                outputFields.stream().map(ViewFieldRef::main).toList(), List.of(), List.of());
        return ReferenceReadProjectionPostProcessor.apply(childService.modelClass(),
                RecordReadProjectionProjector.project((List) records, projection), outputFields);
    }
}
