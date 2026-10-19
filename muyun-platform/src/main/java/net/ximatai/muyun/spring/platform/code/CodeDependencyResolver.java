package net.ximatai.muyun.spring.platform.code;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import java.util.stream.Collectors;

public class CodeDependencyResolver {
    private final FormulaEngine formulas = new FormulaEngine();
    private static final Set<CodeSegmentType> SOURCE_DEPENDENCY_SEGMENT_TYPES = Set.of(
            CodeSegmentType.FIELD_VALUE,
            CodeSegmentType.VALUE_MAPPING,
            CodeSegmentType.CONTEXT_VAR,
            CodeSegmentType.FORMULA
    );

    public boolean dependsOnChangedField(CodeRule rule,
                                         Map<String, Object> beforeContext,
                                         Map<String, Object> afterContext,
                                         Set<String> changedKeys) {
        if (rule == null || rule.getSegments() == null || rule.getSegments().isEmpty()
                || changedKeys == null || changedKeys.isEmpty()) {
            return false;
        }
        Set<String> dependencies = dependencyKeys(rule);
        if (dependencies.isEmpty()) return false;
        return changedKeys.stream()
                .filter(dependencies::contains)
                .anyMatch(key -> !Objects.equals(valueOf(beforeContext, key), valueOf(afterContext, key)));
    }

    Set<String> dependencyKeys(CodeRule rule) {
        if (rule == null || rule.getMode() == CodeMode.MANUAL || rule.getSegments() == null || rule.getSegments().isEmpty()) {
            return Set.of();
        }
        return rule.getSegments().stream()
                .filter(segment -> SOURCE_DEPENDENCY_SEGMENT_TYPES.contains(segment.getSegmentType()))
                .flatMap(segment -> segment.getSegmentType() == CodeSegmentType.FORMULA
                        ? formulaDependencies(segment.getFormulaExpr()).stream()
                        : java.util.stream.Stream.of(segment.getSourceRef()))
                .filter(this::hasText)
                .collect(Collectors.toSet());
    }

    java.util.List<ResolvedCodeRule> orderedRules(java.util.List<ResolvedCodeRule> rules, String entityAlias) {
        var byField = new java.util.TreeMap<String, ResolvedCodeRule>();
        for (var rule : rules) byField.put(rule.rule().getFieldName(), rule);
        var result = new java.util.ArrayList<ResolvedCodeRule>();
        var visited = new java.util.HashSet<String>();
        for (String field : byField.keySet()) visit(field, entityAlias, byField, visited, new java.util.LinkedHashSet<>(), result);
        return java.util.List.copyOf(result);
    }
    private void visit(String field, String entity, Map<String, ResolvedCodeRule> rules, Set<String> visited,
                       Set<String> path, java.util.List<ResolvedCodeRule> result) {
        if (visited.contains(field)) return;
        if (!path.add(field)) throw new IllegalArgumentException("Cyclic business code dependency: " + path + " -> " + field);
        for (String source : dependencyKeys(rules.get(field).rule()).stream().sorted().toList()) {
            String key = source.startsWith(entity + ".") ? source.substring(entity.length() + 1) : source;
            if (rules.containsKey(key)) visit(key, entity, rules, visited, path, result);
        }
        path.remove(field); visited.add(field); result.add(rules.get(field));
    }

    private Set<String> formulaDependencies(String expression) {
        if (expression == null || expression.isBlank()) {
            return Set.of();
        }
        return formulas.referencedFields(expression);
    }

    private Object valueOf(Map<String, Object> context, String key) {
        return context == null || key == null ? null : context.get(key);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
