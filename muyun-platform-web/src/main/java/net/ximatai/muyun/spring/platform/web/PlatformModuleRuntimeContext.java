package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;

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
        Set<NavigatorSourceCapability> navigatorSourceCapabilities,
        ResolvedModuleUiDescriptor uiDescriptor
) {
    /** Source-compatible runtime context constructor before sort partition facts were exposed. */
    public PlatformModuleRuntimeContext(String moduleAlias,
                                        String title,
                                        ModuleKind moduleKind,
                                        ModuleEntryType entryType,
                                        String entryRoute,
                                        String entryExternalUrl,
                                        String mainEntityAlias,
                                        Set<EntityCapability> capabilities,
                                        Set<String> abilities,
                                        List<PlatformModuleRuntimeAction> actions,
                                        Set<NavigatorSourceCapability> navigatorSourceCapabilities,
                                        ResolvedModuleUiDescriptor uiDescriptor) {
        this(moduleAlias, title, moduleKind, entryType, entryRoute, entryExternalUrl, mainEntityAlias,
                capabilities, List.of(), abilities, actions, navigatorSourceCapabilities, uiDescriptor);
    }

    public PlatformModuleRuntimeContext(String moduleAlias,
                                        String title,
                                        ModuleKind moduleKind,
                                        ModuleEntryType entryType,
                                        String entryRoute,
                                        String entryExternalUrl,
                                        String mainEntityAlias,
                                        Set<EntityCapability> capabilities,
                                        Set<String> abilities,
        List<PlatformModuleRuntimeAction> actions) {
        this(moduleAlias, title, moduleKind, entryType, entryRoute, entryExternalUrl, mainEntityAlias,
                capabilities, List.of(), abilities, actions, Set.of(), (ResolvedModuleUiDescriptor) null);
    }

    public PlatformModuleRuntimeContext(String moduleAlias,
                                        String title,
                                        ModuleKind moduleKind,
                                        ModuleEntryType entryType,
                                        String entryRoute,
                                        String entryExternalUrl,
                                        String mainEntityAlias,
                                        Set<EntityCapability> capabilities,
                                        Set<String> abilities,
                                        List<PlatformModuleRuntimeAction> actions,
                                        ModuleUiDefinition uiDefinition) {
        this(moduleAlias, title, moduleKind, entryType, entryRoute, entryExternalUrl, mainEntityAlias,
                capabilities, List.of(), abilities, actions, Set.of(), ModuleUiDescriptorCompiler.compile(uiDefinition));
    }
}
