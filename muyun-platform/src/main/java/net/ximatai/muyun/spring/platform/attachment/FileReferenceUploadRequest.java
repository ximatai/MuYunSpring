package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trusted business context supplied to a file-reference upload policy.
 * File facts are only admission hints; FileServer metadata remains authoritative
 * when the record is saved.
 */
public record FileReferenceUploadRequest(String moduleAlias,
                                         String relationCode,
                                         String fieldName,
                                         Map<String, Object> draft,
                                         FileReferenceUploadFile file,
                                         FileReferenceUploadIntent intent) {
    public FileReferenceUploadRequest {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        relationCode = relationCode == null || relationCode.isBlank() ? null : relationCode.trim();
        if (fieldName == null || fieldName.isBlank()) throw new IllegalArgumentException("file reference fieldName must not be blank");
        fieldName = fieldName.trim();
        // Browser CRUD drafts intentionally retain standard null fields (for example tenantId
        // on a system-scoped create). Map.copyOf rejects null values and would turn a valid
        // upload admission request into an opaque 500 before any policy can inspect it.
        draft = draft == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(draft));
        if (file == null) throw new IllegalArgumentException("file reference upload file must not be null");
        if (intent == null) throw new IllegalArgumentException("file reference upload intent must not be null");
    }
}
