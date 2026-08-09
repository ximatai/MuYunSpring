package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;

import java.util.Set;

/** Runtime descriptor for one declared file-reference field. */
public record DynamicFileReferenceDescriptor(String fieldName,
                                             Set<String> allowedMediaTypes,
                                             Long maxFileSizeBytes,
                                             int maxFiles) {
    public DynamicFileReferenceDescriptor {
        allowedMediaTypes = allowedMediaTypes == null ? Set.of() : Set.copyOf(allowedMediaTypes);
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("file reference maxFiles must be positive");
        }
    }

    public DynamicFileReferenceDescriptor(String fieldName, Set<String> allowedMediaTypes, Long maxFileSizeBytes) {
        this(fieldName, allowedMediaTypes, maxFileSizeBytes, 1);
    }

    public static DynamicFileReferenceDescriptor from(String fieldName, FileReferenceDefinition definition) {
        return new DynamicFileReferenceDescriptor(fieldName, definition.allowedMediaTypes(),
                definition.maxFileSizeBytes(), definition.maxFiles());
    }
}
