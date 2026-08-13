package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;

import java.util.Set;

/** Source-neutral page-runtime fact for one declared file-reference field. */
public record ResolvedFileReferenceFieldDescriptor(ViewFieldRef fieldRef,
                                                   Set<String> allowedMediaTypes,
                                                   Long maxFileSizeBytes,
                                                   int maxFiles,
                                                   FileReferenceStoragePolicy storagePolicy,
                                                   boolean uploadAvailable,
                                                   boolean readAvailable) {
    public ResolvedFileReferenceFieldDescriptor {
        if (fieldRef == null) {
            throw new IllegalArgumentException("file reference field ref must not be null");
        }
        allowedMediaTypes = allowedMediaTypes == null ? Set.of() : Set.copyOf(allowedMediaTypes);
        if (maxFileSizeBytes != null && maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("file reference maxFileSizeBytes must be positive");
        }
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("file reference maxFiles must be positive");
        }
        storagePolicy = storagePolicy == null ? FileReferenceStoragePolicy.MUYUN_FILE_SERVER : storagePolicy;
    }

    public ResolvedFileReferenceFieldDescriptor(ViewFieldRef fieldRef,
                                                Set<String> allowedMediaTypes,
                                                Long maxFileSizeBytes) {
        this(fieldRef, allowedMediaTypes, maxFileSizeBytes, 1, FileReferenceStoragePolicy.MUYUN_FILE_SERVER, false, false);
    }

    public ResolvedFileReferenceFieldDescriptor(ViewFieldRef fieldRef, Set<String> allowedMediaTypes,
                                                Long maxFileSizeBytes, int maxFiles) {
        this(fieldRef, allowedMediaTypes, maxFileSizeBytes, maxFiles, FileReferenceStoragePolicy.MUYUN_FILE_SERVER, false, false);
    }

    public ResolvedFileReferenceFieldDescriptor(ViewFieldRef fieldRef, Set<String> allowedMediaTypes,
                                                Long maxFileSizeBytes, int maxFiles,
                                                FileReferenceStoragePolicy storagePolicy, boolean uploadAvailable) {
        this(fieldRef, allowedMediaTypes, maxFileSizeBytes, maxFiles, storagePolicy, uploadAvailable, false);
    }

    public ResolvedFileReferenceFieldDescriptor withUploadAvailable(boolean value) {
        return new ResolvedFileReferenceFieldDescriptor(fieldRef, allowedMediaTypes, maxFileSizeBytes, maxFiles,
                storagePolicy, value, readAvailable);
    }

    public ResolvedFileReferenceFieldDescriptor withAccess(boolean value) {
        return new ResolvedFileReferenceFieldDescriptor(fieldRef, allowedMediaTypes, maxFileSizeBytes, maxFiles,
                storagePolicy, true, value);
    }

    public static ResolvedFileReferenceFieldDescriptor from(ViewFieldRef fieldRef,
                                                            FileReferenceDefinition definition) {
        return new ResolvedFileReferenceFieldDescriptor(fieldRef, definition.allowedMediaTypes(),
                definition.maxFileSizeBytes(), definition.maxFiles(), definition.storagePolicy(), false, false);
    }
}
