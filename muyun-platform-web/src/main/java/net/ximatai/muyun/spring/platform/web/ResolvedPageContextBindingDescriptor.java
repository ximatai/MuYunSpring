package net.ximatai.muyun.spring.platform.web;

/** Runtime form of a validated page-context flow. */
public record ResolvedPageContextBindingDescriptor(PageContextSource source,
                                                   String sourceKey,
                                                   PageContextTarget target,
                                                   String targetKey,
                                                   String targetNavigatorLevelKey) {
    static ResolvedPageContextBindingDescriptor from(PageContextBindingDefinition definition) {
        return new ResolvedPageContextBindingDescriptor(definition.source(), definition.sourceKey(), definition.target(),
                definition.targetKey(), definition.targetNavigatorLevelKey());
    }
}
