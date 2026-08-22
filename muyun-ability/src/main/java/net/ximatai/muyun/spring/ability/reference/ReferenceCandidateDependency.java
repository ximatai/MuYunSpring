package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;

/** Resolved, source-neutral candidate constraint shared by static and dynamic references. */
public record ReferenceCandidateDependency(String sourceField, String targetField, boolean required) {
    public ReferenceCandidateDependency {
        if (sourceField == null || sourceField.isBlank()) {
            throw new PlatformException("reference candidate dependency sourceField must not be blank");
        }
        if (targetField == null || targetField.isBlank()) {
            throw new PlatformException("reference candidate dependency targetField must not be blank");
        }
        sourceField = sourceField.trim();
        targetField = targetField.trim();
    }

    public static ReferenceCandidateDependency required(String sourceField, String targetField) {
        return new ReferenceCandidateDependency(sourceField, targetField, true);
    }
}
