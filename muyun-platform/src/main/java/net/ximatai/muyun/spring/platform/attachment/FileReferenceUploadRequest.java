package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

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
        draft = draft == null ? Map.of() : Map.copyOf(draft);
        if (file == null) throw new IllegalArgumentException("file reference upload file must not be null");
        if (intent == null) throw new IllegalArgumentException("file reference upload intent must not be null");
    }
}
