package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Complete metadata and presentation facts shared by preview and published-page compilation. */
public record DynamicPageCompilationContext(
        DynamicModuleOverviewMode overviewMode,
        Map<String, String> mainFieldTitles,
        Set<String> requiredMainFields,
        Map<String, DynamicAssociationViewDescriptor> associations
) {
    public DynamicPageCompilationContext {
        overviewMode = overviewMode == null ? DynamicModuleOverviewMode.LIST_CARD : overviewMode;
        mainFieldTitles = Map.copyOf(mainFieldTitles);
        requiredMainFields = Set.copyOf(requiredMainFields);
        associations = Map.copyOf(associations);
    }

    static DynamicPageCompilationContext from(DynamicModuleDescriptor module, DynamicModuleOverviewMode mode) {
        var main = module.entities().stream()
                .filter(entity -> module.mainEntityAlias().equals(entity.entityAlias()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "dynamic runtime has no main entity: " + module.moduleAlias()));
        return new DynamicPageCompilationContext(mode,
                main.fields().stream().collect(Collectors.toMap(DynamicFieldDescriptor::fieldName,
                        DynamicFieldDescriptor::title, (left, right) -> left, LinkedHashMap::new)),
                main.fields().stream().filter(DynamicFieldDescriptor::required)
                        .map(DynamicFieldDescriptor::fieldName).collect(Collectors.toUnmodifiableSet()),
                DynamicPageAssociationCatalog.mainEntityChildAssociations(module));
    }
}
