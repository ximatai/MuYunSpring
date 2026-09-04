package net.ximatai.muyun.spring.platform.module;

import java.util.Set;

/** Read model for the persisted overview mode and its currently derived capability facts. */
public record DynamicModuleOverviewModeSnapshot(
        String moduleAlias,
        Integer moduleVersion,
        DynamicModuleOverviewMode overviewMode,
        String mainMetadataId,
        Integer mainMetadataVersion,
        Set<String> mainCapabilities) {
}
