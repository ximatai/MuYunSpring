package net.ximatai.muyun.spring.platform.web;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source-neutral execution contract for a field control. It deliberately exposes a renderer kind,
 * never an adapter component name or module path.
 */
public record ResolvedFieldControlDescriptor(String alias,
                                             String rendererType,
                                             String valueShape,
                                             Map<String, String> properties,
                                             List<ResolvedFieldControlBindingDescriptor> bindings) {
    private static final Set<String> VALUE_SHAPES = Set.of("SCALAR", "COLLECTION", "COMPOSITE");

    public ResolvedFieldControlDescriptor {
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("field control alias must not be blank");
        if (rendererType == null || rendererType.isBlank()) throw new IllegalArgumentException("field control renderer type must not be blank: " + alias);
        if (valueShape == null || !VALUE_SHAPES.contains(valueShape.trim())) {
            throw new IllegalArgumentException("unsupported field control value shape: " + alias + "." + valueShape);
        }
        alias = alias.trim();
        rendererType = rendererType.trim();
        valueShape = valueShape.trim();
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        if (!"COMPOSITE".equals(valueShape) && !bindings.isEmpty()) {
            throw new IllegalArgumentException("only composite field controls may declare bindings: " + alias);
        }
        if ("COMPOSITE".equals(valueShape) && bindings.isEmpty()) {
            throw new IllegalArgumentException("composite field control requires bindings: " + alias);
        }
        if (bindings.stream().map(ResolvedFieldControlBindingDescriptor::key).distinct().count() != bindings.size()) {
            throw new IllegalArgumentException("field control binding keys must be unique: " + alias);
        }
    }
}
