package net.ximatai.muyun.spring.platform.web;

/** Source-neutral resolved contract for a navigator-scoped main tree resource. */
public record ResolvedPageTreeResourceDescriptor(String resource, String scopeNavigatorKey, String scopeField,
                                                 String scopeRecordField, String scopeRecordEquals,
                                                 String title, String emptyDescription, String createTitle) {
    static ResolvedPageTreeResourceDescriptor from(PageTreeResourceDefinition definition) {
        if (definition == null) return null;
        return new ResolvedPageTreeResourceDescriptor(definition.resource(), definition.scopeNavigatorKey(),
                definition.scopeField(), definition.scopeRecordField(), definition.scopeRecordEquals(),
                definition.title(), definition.emptyDescription(), definition.createTitle());
    }
}
