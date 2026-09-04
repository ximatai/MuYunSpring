package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Map;

/** Optimistic module-scoped write for the dynamic management overview mode. */
public record DynamicModuleOverviewModeSaveCommand(
        DynamicModuleOverviewMode overviewMode,
        Integer expectedModuleVersion,
        Integer expectedMainMetadataVersion,
        Map<EntityCapability, Boolean> capabilitySelections,
        Boolean dataScopeEnabled) {

    public DynamicModuleOverviewModeSaveCommand(DynamicModuleOverviewMode overviewMode,
                                                Integer expectedModuleVersion) {
        this(overviewMode, expectedModuleVersion, null, Map.of(), null);
    }
}
