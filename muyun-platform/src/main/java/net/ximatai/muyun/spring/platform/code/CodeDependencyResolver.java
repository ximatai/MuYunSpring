package net.ximatai.muyun.spring.platform.code;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CodeDependencyResolver {
    private static final Pattern FORMULA_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
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
        if (dependencies.isEmpty()) {
            return rule.getSegments().stream()
                    .anyMatch(segment -> segment.getSegmentType() == CodeSegmentType.FORMULA);
        }
        return changedKeys.stream()
                .filter(dependencies::contains)
                .anyMatch(key -> !Objects.equals(valueOf(beforeContext, key), valueOf(afterContext, key)));
    }

    Set<String> dependencyKeys(CodeRule rule) {
        if (rule == null || rule.getSegments() == null || rule.getSegments().isEmpty()) {
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

    private Set<String> formulaDependencies(String expression) {
        if (expression == null || expression.isBlank()) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>();
        Matcher matcher = FORMULA_PLACEHOLDER.matcher(expression);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (hasText(key)) {
                dependencies.add(key);
            }
        }
        return dependencies;
    }

    private Object valueOf(Map<String, Object> context, String key) {
        return context == null || key == null ? null : context.get(key);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
