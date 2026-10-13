package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.platform.localization.ConfiguredTextSourceProvider;
import org.springframework.stereotype.Component;
import java.util.List;

/** UI producers expose only their server-resolved configured display slots. */
@Component
public class ModuleConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private final PlatformModuleRuntimeContextService modules;
    private StaticModuleDefinitionCatalog declarations;
    public ModuleConfiguredTextSourceProvider(PlatformModuleRuntimeContextService modules) { this.modules = modules; }
    @org.springframework.beans.factory.annotation.Autowired
    public ModuleConfiguredTextSourceProvider(PlatformModuleRuntimeContextService modules, StaticModuleDefinitionCatalog declarations) {
        this(modules); this.declarations = declarations;
    }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        if (declarations != null && declarations.find(moduleAlias).isEmpty() && !modules.dynamicModuleAliases().contains(moduleAlias)) return List.of();
        var descriptor = modules.context(moduleAlias).uiDescriptor();
        if (descriptor == null) return List.of();
        return ModuleUiTextLocations.of(descriptor).stream().map(location -> descriptor.sourceTexts().get(location.bindingKey()))
                .filter(source -> source != null && source.sourceKind() == TextReference.SourceKind.CONFIG).distinct().toList();
    }
}
