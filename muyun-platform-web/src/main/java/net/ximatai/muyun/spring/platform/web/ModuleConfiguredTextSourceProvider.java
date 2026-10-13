package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.platform.localization.ConfiguredTextSourceProvider;
import org.springframework.stereotype.Component;
import java.util.List;

/** UI producers expose only their server-resolved configured display slots. */
@Component
public class ModuleConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private final PlatformModuleRuntimeContextService modules;
    public ModuleConfiguredTextSourceProvider(PlatformModuleRuntimeContextService modules) { this.modules = modules; }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        var descriptor = modules.context(moduleAlias).uiDescriptor();
        if (descriptor == null) return List.of();
        return ModuleUiTextLocations.of(descriptor).stream().map(location -> descriptor.sourceTexts().get(location.bindingKey()))
                .filter(source -> source != null && source.sourceKind() == TextReference.SourceKind.CONFIG).distinct().toList();
    }
}
