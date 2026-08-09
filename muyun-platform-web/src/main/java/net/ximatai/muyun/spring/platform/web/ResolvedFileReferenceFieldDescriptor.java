package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;

import java.util.Set;

/** Source-neutral page-runtime fact for one declared file-reference field. */
public record ResolvedFileReferenceFieldDescriptor(ViewFieldRef fieldRef,
                                                   Set<String> allowedMediaTypes,
                                                   Long maxFileSizeBytes) {
    public ResolvedFileReferenceFieldDescriptor {
        if (fieldRef == null) {
            throw new IllegalArgumentException("file reference field ref must not be null");
        }
        allowedMediaTypes = allowedMediaTypes == null ? Set.of() : Set.copyOf(allowedMediaTypes);
        if (maxFileSizeBytes != null && maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("file reference maxFileSizeBytes must be positive");
        }
    }

    public static ResolvedFileReferenceFieldDescriptor from(ViewFieldRef fieldRef,
                                                            FileReferenceDefinition definition) {
        return new ResolvedFileReferenceFieldDescriptor(fieldRef, definition.allowedMediaTypes(),
                definition.maxFileSizeBytes());
    }
}
