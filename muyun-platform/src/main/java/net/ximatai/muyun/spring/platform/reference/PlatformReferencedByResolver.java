package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencedByResolver;
import net.ximatai.muyun.spring.ability.reference.StaticReferencedByResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bridges static {@code @ReferencedBy} declarations to their unique CRUD source services. */
public final class PlatformReferencedByResolver implements ReferencedByResolver {
    private final StaticAbilityCatalog abilities;

    public PlatformReferencedByResolver(StaticAbilityCatalog abilities) {
        this.abilities = abilities;
        validateDeclarations();
    }

    @Override
    public void populate(CrudAbility<?> ability, EntityContract entity) {
        populateAll(ability, entity == null ? List.of() : List.of(entity));
    }

    @Override
    public void populateAll(CrudAbility<?> ability, Collection<? extends EntityContract> entities) {
        if (ability == null || entities == null || entities.isEmpty()) return;
        Class<?> targetModel = ability.modelClass() == null ? entities.iterator().next().getClass() : ability.modelClass();
        for (StaticReferencedByResolver.ReferencedByPlan plan : StaticReferencedByResolver.plans(targetModel)) {
            CrudAbility<?> sourceAbility = requireSourceAbility(targetModel, plan.sourceModel());
            List<String> targetIds = entities.stream().map(EntityContract::getId)
                    .filter(id -> id != null && !id.isBlank()).distinct().toList();
            Map<String, List<? extends EntityContract>> rowsByTarget = sourceRows(sourceAbility, plan.sourceField(), targetIds);
            for (EntityContract entity : entities) {
                StaticReferencedByResolver.writeLoadedValue(entity, plan.fieldName(),
                        rowsByTarget.getOrDefault(entity.getId(), List.of()));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, List<? extends EntityContract>> sourceRows(CrudAbility<?> sourceAbility,
                                                                            String sourceField,
                                                                            List<String> targetIds) {
        if (targetIds.isEmpty()) return Map.of();
        Map<String, List<EntityContract>> rows = new LinkedHashMap<>();
        for (EntityContract row : (List<? extends EntityContract>) ((CrudAbility) sourceAbility)
                .list(Criteria.of().in(sourceField, targetIds))) {
            Object targetId = net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver
                    .readLoadedValue(row, sourceField);
            if (targetId != null) rows.computeIfAbsent(String.valueOf(targetId), ignored -> new java.util.ArrayList<>()).add(row);
        }
        Map<String, List<? extends EntityContract>> immutable = new LinkedHashMap<>();
        rows.forEach((id, values) -> immutable.put(id, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private void validateDeclarations() {
        for (CrudAbility<?> ability : abilities.abilities()) {
            Class<?> targetModel = ability.modelClass();
            for (StaticReferencedByResolver.ReferencedByPlan plan : StaticReferencedByResolver.plans(targetModel)) {
                requireSourceAbility(targetModel, plan.sourceModel());
            }
        }
    }

    private CrudAbility<?> requireSourceAbility(Class<?> targetModel, Class<?> sourceModel) {
        return abilities.findByModel(sourceModel).orElseThrow(() -> new PlatformException(
                "@ReferencedBy source service is not registered: "
                        + targetModel.getName() + " <- " + sourceModel.getName()));
    }
}
