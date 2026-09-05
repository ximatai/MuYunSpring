package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.PageNavigatorSourceCapabilityResolver;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/** Bridges static Web projection declarations into the source-capability contract used by pages. */
@Service
public class PlatformPageNavigatorSourceCapabilityResolver implements PageNavigatorSourceCapabilityResolver {
    private final StaticModuleDefinitionCatalog staticModuleCatalog;
    private final DynamicRecordService dynamicRecordService;
    private final RegisteredWebEndpointCatalog endpointCatalog;

    @Autowired
    public PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog,
                                                          ObjectProvider<DynamicRecordService> dynamicRecordService,
                                                          ObjectProvider<RegisteredWebEndpointCatalog> endpointCatalog) {
        this(staticModuleCatalog, dynamicRecordService == null ? null : dynamicRecordService.getIfAvailable(),
                endpointCatalog == null ? null : endpointCatalog.getIfAvailable());
    }

    public PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog) {
        this(staticModuleCatalog, (DynamicRecordService) null, (RegisteredWebEndpointCatalog) null);
    }

    PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog,
                                                   DynamicRecordService dynamicRecordService) {
        this(staticModuleCatalog, dynamicRecordService, null);
    }

    PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog,
                                                   DynamicRecordService dynamicRecordService,
                                                   RegisteredWebEndpointCatalog endpointCatalog) {
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicRecordService = dynamicRecordService;
        this.endpointCatalog = endpointCatalog;
    }

    @Override
    public boolean supports(String moduleAlias, boolean tree) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (staticModuleCatalog.find(validAlias).isPresent()) {
            return endpointCatalog != null && endpointCatalog.endpoints().stream()
                    .filter(endpoint -> validAlias.equals(endpoint.definition().moduleAlias()))
                    .filter(endpoint -> endpoint.definition().action() == net.ximatai.muyun.spring.common.platform.PlatformAction.REFERENCE)
                    .filter(endpoint -> endpoint.definition().method() == org.springframework.web.bind.annotation.RequestMethod.POST)
                    .anyMatch(endpoint -> endpoint.definition().path().equals("/" + validAlias
                            + (tree ? "/navigator/reference/tree/query" : "/navigator/reference/query")));
        }
        return dynamicNavigatorSupports(validAlias, tree);
    }

    /** Validates static page sources after the endpoint registrar has populated its catalog. */
    public void validateStaticSources() {
        for (StaticModuleDefinition definition : staticModuleCatalog.definitions()) {
            PageNavigatorDefinition navigator = StaticPageNavigatorSourceValidator.navigator(definition.uiDefinition());
            if (navigator == null) continue;
            for (PageNavigatorLevelDefinition level : navigator.levels()) {
                // Dynamic modules may be published after startup; their sources are checked at publication/runtime.
                if (staticModuleCatalog.find(level.sourceModuleAlias()).isEmpty()) continue;
                boolean tree = level.kind() == PageNavigatorKind.TREE;
                if (!supports(level.sourceModuleAlias(), tree)) {
                    throw new IllegalStateException("navigator source endpoint is unavailable: page="
                            + definition.moduleAlias() + ", level=" + level.key() + ", source="
                            + level.sourceModuleAlias() + ", tree=" + tree);
                }
            }
        }
    }

    @Override
    public boolean supportsManagement(String moduleAlias, String editorSurface) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return staticModuleCatalog.find(validAlias)
                .filter(this::supportsStandardManagementActions)
                .filter(source -> supportsEditor(source, editorSurface))
                .isPresent();
    }

    private boolean supportsStandardManagementActions(StaticModuleDefinition source) {
        Set<String> available = source.actions().stream()
                .map(action -> action.actionCode().toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return available.containsAll(Set.of("CREATE", "UPDATE", "DELETE"));
    }

    private boolean supportsEditor(StaticModuleDefinition source, String editorSurface) {
        ModuleUiDefinition ui = source.uiDefinition();
        if (ui == null) return false;
        if (editorSurface != null) {
            return ui.editorSurfaces().stream().anyMatch(surface -> editorSurface.equals(surface.key()));
        }
        if (ui.defaultEditor() != null) return true;
        if (ui.page() == null) return false;
        PageDetailDefinition detail = switch (ui.page()) {
            case FlatManagementPageDefinition page -> page.detail();
            case ListDetailCardPageDefinition page -> page.detail();
            case TreeManagementPageDefinition page -> page.detail();
        };
        return detail != null && detail.editor() != null;
    }

    private boolean dynamicNavigatorSupports(String moduleAlias, boolean tree) {
        if (dynamicRecordService == null) return false;
        DynamicModuleDescriptor descriptor;
        try {
            descriptor = dynamicRecordService.describe(moduleAlias);
        } catch (ModuleDefinitionException ignored) {
            // An alias with neither a static definition nor a published dynamic descriptor simply
            // does not expose a navigator projection.
            return false;
        }
        DynamicEntityDescriptor mainEntity = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .findFirst()
                .orElse(null);
        return mainEntity != null && mainEntity.capabilities().contains(EntityCapability.REFERENCE.name())
                && (!tree || mainEntity.capabilities().contains(EntityCapability.TREE.name()));
    }
}
