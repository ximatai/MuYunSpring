package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Maps a selected target field to a read-only projection on the source record. */
public record ResolvedReferenceDisplayProjectionDescriptor(String targetField, String outputField) {
    public ResolvedReferenceDisplayProjectionDescriptor {
        targetField = PlatformNameRules.requireFieldName(targetField, "reference projection target field");
        outputField = PlatformNameRules.requireFieldName(outputField, "reference projection output field");
    }
}
