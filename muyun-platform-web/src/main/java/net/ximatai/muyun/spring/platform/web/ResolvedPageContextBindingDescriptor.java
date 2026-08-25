package net.ximatai.muyun.spring.platform.web;

/** Runtime form of a validated page-context flow. */
public record ResolvedPageContextBindingDescriptor(PageContextSource source,
                                                   String sourceKey,
                                                   PageContextTarget target,
                                                   String targetKey,
                                                   String targetNavigatorLevelKey,
                                                   String targetPickerFieldKey,
                                                   NavigatorListQueryMode navigatorListQueryMode) {
    public ResolvedPageContextBindingDescriptor {
        if (source == PageContextSource.NAVIGATOR && target == PageContextTarget.LIST_QUERY
                && navigatorListQueryMode == null) {
            navigatorListQueryMode = NavigatorListQueryMode.REQUIRED_SCOPE;
        }
    }
    public ResolvedPageContextBindingDescriptor(PageContextSource source, String sourceKey, PageContextTarget target,
                                                String targetKey, String targetNavigatorLevelKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, null, null);
    }

    public ResolvedPageContextBindingDescriptor(PageContextSource source, String sourceKey, PageContextTarget target,
                                                String targetKey, String targetNavigatorLevelKey,
                                                String targetPickerFieldKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, targetPickerFieldKey, null);
    }
    static ResolvedPageContextBindingDescriptor from(PageContextBindingDefinition definition) {
        return new ResolvedPageContextBindingDescriptor(definition.source(), definition.sourceKey(), definition.target(),
                definition.targetKey(), definition.targetNavigatorLevelKey(), definition.targetPickerFieldKey(),
                definition.navigatorListQueryMode());
    }
}
