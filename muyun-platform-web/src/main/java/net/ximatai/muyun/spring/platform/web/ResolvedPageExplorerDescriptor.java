package net.ximatai.muyun.spring.platform.web;

/** Source-neutral explorer slot content. */
public record ResolvedPageExplorerDescriptor(String title, String searchPlaceholder, String emptyDescription,
                                             String recordLabel, String fallbackTitle, String titleField,
                                             String secondaryField, boolean mutedWhenDisabled) {
    static ResolvedPageExplorerDescriptor from(PageExplorerDefinition definition) {
        if (definition == null) return null;
        return new ResolvedPageExplorerDescriptor(definition.title(), definition.searchPlaceholder(),
                definition.emptyDescription(), definition.recordLabel(), definition.fallbackTitle(),
                definition.titleField(), definition.secondaryField(), definition.mutedWhenDisabled());
    }
}
