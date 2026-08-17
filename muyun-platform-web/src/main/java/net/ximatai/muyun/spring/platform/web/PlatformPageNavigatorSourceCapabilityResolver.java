package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.platform.ui.PageNavigatorSourceCapabilityResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Bridges static Web projection declarations into the source-capability contract used by pages. */
@Service
public class PlatformPageNavigatorSourceCapabilityResolver implements PageNavigatorSourceCapabilityResolver {
    private final StaticModuleDefinitionCatalog staticModuleCatalog;
    private final DynamicRecordService dynamicRecordService;

    @Autowired
    public PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog,
                                                          ObjectProvider<DynamicRecordService> dynamicRecordService) {
        this(staticModuleCatalog, dynamicRecordService == null ? null : dynamicRecordService.getIfAvailable());
    }

    public PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog) {
        this(staticModuleCatalog, (DynamicRecordService) null);
    }

    PlatformPageNavigatorSourceCapabilityResolver(StaticModuleDefinitionCatalog staticModuleCatalog,
                                                   DynamicRecordService dynamicRecordService) {
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicRecordService = dynamicRecordService;
    }

    @Override
    public Set<NavigatorSourceCapability> capabilities(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return staticModuleCatalog.find(validAlias)
                .map(StaticModuleDefinition::navigatorSourceCapabilities)
                .orElseGet(() -> dynamicNavigatorSourceCapabilities(validAlias));
    }

    private Set<NavigatorSourceCapability> dynamicNavigatorSourceCapabilities(String moduleAlias) {
        if (dynamicRecordService == null) return Set.of();
        DynamicModuleDescriptor descriptor;
        try {
            descriptor = dynamicRecordService.describe(moduleAlias);
        } catch (ModuleDefinitionException ignored) {
            // An alias with neither a static definition nor a published dynamic descriptor simply
            // does not expose a navigator projection.
            return Set.of();
        }
        DynamicEntityDescriptor mainEntity = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .findFirst()
                .orElse(null);
        if (mainEntity == null || !mainEntity.capabilities().contains(EntityCapability.REFERENCE.name())) {
            return Set.of();
        }
        if (mainEntity.capabilities().contains(EntityCapability.TREE.name())) {
            return Set.of(NavigatorSourceCapability.REFERENCE_QUERY, NavigatorSourceCapability.REFERENCE_TREE);
        }
        return Set.of(NavigatorSourceCapability.REFERENCE_QUERY);
    }
}
