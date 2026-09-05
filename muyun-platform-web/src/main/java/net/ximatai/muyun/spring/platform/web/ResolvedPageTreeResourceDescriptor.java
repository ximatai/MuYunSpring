package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral resolved contract for a navigator-scoped main tree resource. */
public record ResolvedPageTreeResourceDescriptor(String resource, String scopeNavigatorKey, String scopeField,
                                                 String scopeRecordField, String scopeRecordEquals,
                                                 String title, String emptyDescription, String createTitle,
                                                 List<String> sortPartitionFields) {
    public ResolvedPageTreeResourceDescriptor {
        sortPartitionFields = sortPartitionFields == null ? List.of() : List.copyOf(sortPartitionFields);
    }

    static ResolvedPageTreeResourceDescriptor from(PageTreeResourceDefinition definition,
                                                   List<String> sortPartitionFields) {
        if (definition == null) return null;
        return new ResolvedPageTreeResourceDescriptor(definition.resource(), definition.scopeNavigatorKey(),
                definition.scopeField(), definition.scopeRecordField(), definition.scopeRecordEquals(),
                definition.title(), definition.emptyDescription(), definition.createTitle(), sortPartitionFields);
    }
}
