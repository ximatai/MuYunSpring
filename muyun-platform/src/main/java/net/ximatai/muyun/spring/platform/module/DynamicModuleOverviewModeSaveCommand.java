package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Map;

/** Module-scoped write for the dynamic management overview mode. */
public record DynamicModuleOverviewModeSaveCommand(
        DynamicModuleOverviewMode overviewMode,
        Integer expectedMainMetadataVersion,
        Map<EntityCapability, Boolean> capabilitySelections,
        Boolean dataScopeEnabled) { }
