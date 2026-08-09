package net.ximatai.muyun.spring.dynamic.metadata;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Source-neutral constraints for one persisted MuYunFileServer file-reference field.
 */
public record FileReferenceDefinition(Set<String> allowedMediaTypes, Long maxFileSizeBytes, int maxFiles) {
    public FileReferenceDefinition {
        allowedMediaTypes = normalizeMediaTypes(allowedMediaTypes);
        if (maxFileSizeBytes != null && maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("file reference maxFileSizeBytes must be positive");
        }
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("file reference maxFiles must be positive");
        }
    }

    public FileReferenceDefinition(Set<String> allowedMediaTypes, Long maxFileSizeBytes) {
        this(allowedMediaTypes, maxFileSizeBytes, 1);
    }

    public static FileReferenceDefinition unrestricted() {
        return new FileReferenceDefinition(Set.of(), null, 1);
    }

    private static Set<String> normalizeMediaTypes(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("file reference media type must not be blank");
            }
            String mediaType = value.trim().toLowerCase(Locale.ROOT);
            if (!mediaType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
                throw new IllegalArgumentException("invalid file reference media type: " + value);
            }
            normalized.add(mediaType);
        }
        return Set.copyOf(normalized);
    }
}
