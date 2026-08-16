package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral resolved page-root descriptor. Only slots legal for its template may be present. */
public record ResolvedModulePageDescriptor(ModulePageTemplate template,
                                           ResolvedPageExplorerDescriptor explorer,
                                           ResolvedPageNavigatorDescriptor navigator,
                                           ResolvedPageListDescriptor list,
                                           ResolvedPageDetailDescriptor detail,
                                           List<PageTrait> traits) {
    public ResolvedModulePageDescriptor {
        if (template == null) throw new IllegalArgumentException("page template must not be null");
        traits = traits == null ? List.of() : List.copyOf(traits);
        switch (template) {
            case FLAT_MANAGEMENT -> {
                if (explorer == null || detail == null || list != null) {
                    throw new IllegalArgumentException("flat management requires optional navigator and explorer/detail/traits slots");
                }
            }
            case LIST_DETAIL_CARD -> {
                if (list == null || detail == null || explorer != null) {
                    throw new IllegalArgumentException("list/detail card requires list/detail/traits slots");
                }
            }
            case TREE_MANAGEMENT -> {
                if (explorer != null || navigator != null || list != null || detail == null) {
                    throw new IllegalArgumentException("tree/detail card requires detail/traits slots");
                }
            }
        }
    }

    /** Replaces only the resolved navigator slots after request-scoped descriptor resolution. */
    public ResolvedModulePageDescriptor withNavigator(ResolvedPageNavigatorDescriptor resolvedNavigator) {
        return new ResolvedModulePageDescriptor(template, explorer, resolvedNavigator, list, detail, traits);
    }
}
