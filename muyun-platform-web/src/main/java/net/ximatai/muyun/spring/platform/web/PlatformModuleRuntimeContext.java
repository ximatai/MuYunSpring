package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;

import java.util.List;
import java.util.Set;

public record PlatformModuleRuntimeContext(
        String moduleAlias,
        String title,
        ModuleKind moduleKind,
        ModuleEntryType entryType,
        String entryRoute,
        String entryExternalUrl,
        String mainEntityAlias,
        Set<EntityCapability> capabilities,
        List<String> sortPartitionFields,
        Set<String> abilities,
        List<PlatformModuleRuntimeAction> actions,
        ResolvedModuleUiDescriptor uiDescriptor
) {
}
