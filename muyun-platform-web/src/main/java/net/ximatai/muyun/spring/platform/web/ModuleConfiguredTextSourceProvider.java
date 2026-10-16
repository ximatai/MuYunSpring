package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.platform.localization.ConfiguredTextSourceProvider;
import org.springframework.stereotype.Component;
import java.util.List;

/** UI producers expose only their server-resolved configured display slots. */
@Component
public class ModuleConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private final PlatformModuleRuntimeContextService modules;
    private final StaticModuleDefinitionCatalog declarations;
    @org.springframework.beans.factory.annotation.Autowired
    public ModuleConfiguredTextSourceProvider(PlatformModuleRuntimeContextService modules, StaticModuleDefinitionCatalog declarations) {
        this.modules = java.util.Objects.requireNonNull(modules, "modules");
        this.declarations = java.util.Objects.requireNonNull(declarations, "declarations");
    }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        if (declarations.find(moduleAlias).isEmpty() && !modules.dynamicModuleAliases().contains(moduleAlias)) return List.of();
        var context = modules.context(moduleAlias);
        var descriptor = context.uiDescriptor();
        var sources = new java.util.ArrayList<TextReference>();
        if (descriptor != null) ModuleUiTextLocations.of(descriptor).stream()
                .map(location -> descriptor.sourceTexts().get(location.bindingKey())).forEach(sources::add);
        context.actions().stream().map(PlatformModuleRuntimeAction::titleSource).forEach(sources::add);
        return sources.stream().filter(source -> source != null && source.sourceKind() == TextReference.SourceKind.CONFIG)
                .distinct().toList();
    }
}
