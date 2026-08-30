package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;

/** The dynamic association namespace that a management page may project into its detail slot. */
final class DynamicPageAssociationCatalog {
    private DynamicPageAssociationCatalog() {
    }

    /**
     * A management page is rooted at the module main entity, so only its direct child associations
     * may become detail relations.  Association codes are page-tree identifiers and must therefore
     * be unique after this scope is applied.
     */
    static Map<String, DynamicAssociationViewDescriptor> mainEntityChildAssociations(
            DynamicModuleDescriptor module) {
        return module.associationViews().stream()
                .filter(view -> module.mainEntityAlias().equals(view.sourceEntityAlias()))
                .filter(view -> view.relationCode() != null && !view.relationCode().isBlank())
                .collect(java.util.stream.Collectors.toMap(DynamicAssociationViewDescriptor::code,
                        java.util.function.Function.identity(), (left, right) -> {
                            throw new IllegalStateException("dynamic main-entity association code duplicated: "
                                    + module.moduleAlias() + "." + left.code());
                        }, LinkedHashMap::new));
    }
}
