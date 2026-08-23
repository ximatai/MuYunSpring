package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes declared row-to-row calculation formulas for one static aggregate child relation. */
final class ChildFormulaReconciler {
    private final FormulaEngine engine = new FormulaEngine();

    <C extends EntityContract> void reconcile(String relationCode,
                                               List<C> incoming,
                                               List<C> existing,
                                               List<AggregateChildFormulaDefinition> definitions) {
        if (relationCode == null || relationCode.isBlank() || incoming == null || incoming.isEmpty()
                || definitions == null || definitions.isEmpty()) {
            return;
        }
        List<AggregateChildFormulaDefinition> relationRules = definitions.stream()
                .filter(definition -> definition != null && relationCode.equals(definition.relationCode()))
                .toList();
        if (relationRules.isEmpty()) {
            return;
        }

        List<Map<String, Object>> rows = incoming.stream().map(this::values).collect(Collectors.toCollection(ArrayList::new));
        Map<String, C> existingById = existing == null ? Map.of() : existing.stream()
                .filter(value -> value.getId() != null && !value.getId().isBlank())
                .collect(Collectors.toMap(EntityContract::getId, value -> value, (left, right) -> left, LinkedHashMap::new));
        for (AggregateChildFormulaDefinition definition : relationRules) {
            var rule = definition.rule();
            String target = engine.assignedFields(rule.expression()).stream()
                    .filter(path -> path.startsWith(relationCode + "."))
                    .findFirst()
                    .orElseThrow(() -> new FormulaEvaluationException("FORMULA_OTHERS_TARGET_REQUIRED",
                            "row-to-row calculation requires one child target"));
            String property = target.substring(relationCode.length() + 1);
            List<Integer> changes = changedRows(incoming, rows, existingById, definition.triggerFields()).stream()
                    .filter(index -> engine.matchesRowSetCondition(rule.expression(),
                            FormulaRuntimeData.of(new LinkedHashMap<>(), Map.of(relationCode, rows))
                                    .withChangeScope(relationCode, rows.get(index))))
                    .toList();
            if (changes.isEmpty()) {
                continue;
            }
            if (changes.size() > 1) {
                throw new FormulaEvaluationException("FORMULA_CHANGE_SCOPE_AMBIGUOUS", target,
                        "row-to-row calculation requires exactly one changed child row: " + target);
            }
            Map<String, Object> source = rows.get(changes.getFirst());
            var result = engine.execute(List.of(rule), FormulaRuntimeData.of(new LinkedHashMap<>(), Map.of(relationCode, rows))
                    .withChangeScope(relationCode, source));
            if (result.report().hasErrors()) {
                throw new FormulaEvaluationException("FORMULA_RECONCILE_FAILED", target,
                        result.report().errors().getFirst().message());
            }
            for (int index = 0; index < incoming.size(); index++) {
                write(incoming.get(index), property, rows.get(index).get(property));
            }
        }
    }

    private <C extends EntityContract> List<Integer> changedRows(List<C> incoming,
                                                                   List<Map<String, Object>> rows,
                                                                   Map<String, C> existingById,
                                                                   List<String> triggerFields) {
        List<Integer> changed = new ArrayList<>();
        for (int index = 0; index < incoming.size(); index++) {
            C child = incoming.get(index);
            C existing = child.getId() == null ? null : existingById.get(child.getId());
            boolean changedTrigger = false;
            for (String property : triggerFields) {
                if (!Objects.equals(existing == null ? null : read(existing, property), rows.get(index).get(property))) {
                    changedTrigger = true;
                    break;
                }
            }
            if (changedTrigger) {
                changed.add(index);
            }
        }
        return changed;
    }

    private Map<String, Object> values(Object source) {
        Map<String, Object> values = new LinkedHashMap<>();
        descriptors(source.getClass()).forEach((name, descriptor) -> values.put(name, read(source, descriptor)));
        return values;
    }

    private Object read(Object source, String property) {
        PropertyDescriptor descriptor = descriptors(source.getClass()).get(property);
        return descriptor == null ? null : read(source, descriptor);
    }

    private Object read(Object source, PropertyDescriptor descriptor) {
        try {
            return descriptor.getReadMethod().invoke(source);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new PlatformException("cannot read child formula property: " + descriptor.getName(), exception);
        }
    }

    private void write(Object target, String property, Object value) {
        PropertyDescriptor descriptor = descriptors(target.getClass()).get(property);
        if (descriptor == null || descriptor.getWriteMethod() == null) {
            throw new FormulaEvaluationException("FORMULA_FIELD_NOT_WRITABLE", property,
                    "child formula property is not writable: " + property);
        }
        try {
            descriptor.getWriteMethod().invoke(target, value);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new PlatformException("cannot write child formula property: " + property, exception);
        }
    }

    private Map<String, PropertyDescriptor> descriptors(Class<?> type) {
        try {
            return java.util.Arrays.stream(Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors())
                    .filter(descriptor -> descriptor.getReadMethod() != null)
                    .collect(Collectors.toMap(PropertyDescriptor::getName, descriptor -> descriptor,
                            (left, right) -> left, LinkedHashMap::new));
        } catch (IntrospectionException exception) {
            throw new PlatformException("cannot inspect child formula model: " + type.getName(), exception);
        }
    }
}
