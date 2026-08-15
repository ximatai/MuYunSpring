package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral navigator contract consumed by every page template. */
public record ResolvedPageNavigatorDescriptor(List<ResolvedPageNavigatorLevelDescriptor> levels) {
    public ResolvedPageNavigatorDescriptor {
        levels = levels == null ? List.of() : List.copyOf(levels);
    }

    static ResolvedPageNavigatorDescriptor from(PageNavigatorDefinition definition) {
        if (definition == null) return null;
        return new ResolvedPageNavigatorDescriptor(definition.levels().stream()
                .map(ResolvedPageNavigatorLevelDescriptor::from).toList());
    }
}
