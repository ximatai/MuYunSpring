package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;

import java.util.Set;

/** Runtime descriptor for one single-file field declared by dynamic metadata. */
public record DynamicFileReferenceDescriptor(String fieldName,
                                             Set<String> allowedMediaTypes,
                                             Long maxFileSizeBytes) {
    public DynamicFileReferenceDescriptor {
        allowedMediaTypes = allowedMediaTypes == null ? Set.of() : Set.copyOf(allowedMediaTypes);
    }

    public static DynamicFileReferenceDescriptor from(String fieldName, FileReferenceDefinition definition) {
        return new DynamicFileReferenceDescriptor(fieldName, definition.allowedMediaTypes(),
                definition.maxFileSizeBytes());
    }
}
