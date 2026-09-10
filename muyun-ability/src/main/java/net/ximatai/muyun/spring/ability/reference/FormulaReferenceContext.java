package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaRule;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, server-side-only reference inputs for a main-record formula run. */
public final class FormulaReferenceContext {
    private final Map<String, FormulaReferencePathCompiler.CompiledPath> paths;
    private final ReferenceTargetResolver resolver;

    private FormulaReferenceContext(Map<String, FormulaReferencePathCompiler.CompiledPath> paths,
                                    ReferenceTargetResolver resolver) {
        this.paths = Map.copyOf(paths);
        this.resolver = resolver;
    }

    public static FormulaReferenceContext compile(ReferenceTarget root, List<FormulaRule> rules,
                                                  ReferenceTargetResolver resolver) {
        return compile(root, rules, resolver, Set.of());
    }

    /** Existing child-table prefixes are deliberately excluded from this explicit reference surface. */
    public static FormulaReferenceContext compile(ReferenceTarget root, List<FormulaRule> rules,
                                                  ReferenceTargetResolver resolver, Set<String> childTablePrefixes) {
        FormulaEngine engine = new FormulaEngine();
        LinkedHashMap<String, FormulaReferencePathCompiler.CompiledPath> paths = new LinkedHashMap<>();
        for (FormulaRule rule : rules == null ? List.<FormulaRule>of() : rules) {
            if (rule == null || !rule.enabled() || rule.expression() == null) continue;
            for (String input : engine.valueSideReferencedFields(rule.expression())) {
                if (!input.contains(".")) continue;
                int dot = input.indexOf('.');
                if (childTablePrefixes != null && childTablePrefixes.contains(input.substring(0, dot))) continue;
                // Leave ordinary unknown/child-style dotted inputs to the established formula
                // declaration validator.  Only an explicit first-hop reference enters this
                // separate scalar context.
                if (resolver.referencePlan(root, input.substring(0, dot)).isEmpty()) continue;
                paths.computeIfAbsent(input, path -> FormulaReferencePathCompiler.compile(root, path, resolver));
            }
        }
        return new FormulaReferenceContext(paths, resolver);
    }

    public boolean isEmpty() { return paths.isEmpty(); }

    public List<FormulaFieldDefinition> fields() {
        return paths.values().stream().map(FormulaReferencePathCompiler.CompiledPath::field).toList();
    }

    public Set<String> paths() { return Set.copyOf(paths.keySet()); }

    /** Resolves against the current formula main values; no caller-provided dotted values participate. */
    public Map<String, Object> resolve(Map<String, Object> mainValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormulaReferencePathCompiler.CompiledPath compiled : paths.values()) {
            Object rootValue = mainValues == null ? null : mainValues.get(compiled.loadPath().sourceField());
            if (rootValue instanceof Collection<?> || rootValue != null && rootValue.getClass().isArray()) {
                throw new IllegalArgumentException("formula reference root must contain one id: "
                        + compiled.loadPath().sourceField());
            }
            String id = rootValue == null ? null : String.valueOf(rootValue).trim();
            Object value = id == null || id.isBlank() ? null : ReferenceLoadReader.read(compiled.loadPath(), List.of(id),
                    target -> resolver.resolve(target).orElse(null));
            values.put(compiled.path(), value);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
