package net.ximatai.muyun.spring.platform.web;

/** Source-neutral resolved content for {@link ModulePageTemplate#FLAT_MANAGEMENT}. */
public record ResolvedFlatManagementTemplateDescriptor(String explorerTitle,
                                                       String explorerSearchPlaceholder,
                                                       String emptyDescription,
                                                       String detailEmptyDescription,
                                                       String createTitle,
                                                       String recordLabel,
                                                       String fallbackTitle) {
    static ResolvedFlatManagementTemplateDescriptor from(FlatManagementTemplateDefinition definition) {
        if (definition == null) return null;
        return new ResolvedFlatManagementTemplateDescriptor(definition.explorerTitle(),
                definition.explorerSearchPlaceholder(), definition.emptyDescription(),
                definition.detailEmptyDescription(), definition.createTitle(), definition.recordLabel(),
                definition.fallbackTitle());
    }
}
