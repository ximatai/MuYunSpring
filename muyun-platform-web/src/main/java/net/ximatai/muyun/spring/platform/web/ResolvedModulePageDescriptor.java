package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral resolved page-root descriptor. Only slots legal for its template may be present. */
public record ResolvedModulePageDescriptor(ModulePageTemplate template,
                                           ResolvedPageExplorerDescriptor explorer,
                                           ResolvedPageNavigatorDescriptor navigator,
                                           ResolvedPageListDescriptor list,
                                           ResolvedPageTreeResourceDescriptor treeResource,
                                           ResolvedPageDetailDescriptor detail,
                                           List<PageTrait> traits, List<String> quickSearchFields,
                                           List<ResolvedPageActionDescriptor> actions, boolean managedActions) {
    public ResolvedModulePageDescriptor(ModulePageTemplate template, ResolvedPageExplorerDescriptor explorer,
            ResolvedPageNavigatorDescriptor navigator, ResolvedPageListDescriptor list,
            ResolvedPageTreeResourceDescriptor treeResource, ResolvedPageDetailDescriptor detail, List<PageTrait> traits,
            List<String> quickSearchFields, List<ResolvedPageActionDescriptor> actions) {
        this(template, explorer, navigator, list, treeResource, detail, traits, quickSearchFields, actions, false);
    }
    public ResolvedModulePageDescriptor(ModulePageTemplate template, ResolvedPageExplorerDescriptor explorer,
            ResolvedPageNavigatorDescriptor navigator, ResolvedPageListDescriptor list,
            ResolvedPageTreeResourceDescriptor treeResource, ResolvedPageDetailDescriptor detail, List<PageTrait> traits) {
        this(template, explorer, navigator, list, treeResource, detail, traits, null, List.of());
    }
    public ResolvedModulePageDescriptor {
        quickSearchFields = quickSearchFields == null ? null : List.copyOf(quickSearchFields);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (template == null) throw new IllegalArgumentException("page template must not be null");
        traits = traits == null ? List.of() : List.copyOf(traits);
        switch (template) {
            case FLAT_MANAGEMENT -> {
                if (explorer == null || detail == null || list != null || treeResource != null) {
                    throw new IllegalArgumentException("flat management requires optional navigator and explorer/detail/traits slots");
                }
            }
            case LIST_DETAIL_CARD -> {
                if (list == null || detail == null || explorer != null || treeResource != null) {
                    throw new IllegalArgumentException("list/detail card requires list/detail/traits slots");
                }
            }
            case TREE_MANAGEMENT -> {
                if (list != null || detail == null) {
                    throw new IllegalArgumentException("tree/detail card requires optional navigator and detail/traits slots");
                }
            }
        }
    }

    /** Replaces only the resolved navigator slots after request-scoped descriptor resolution. */
    public ResolvedModulePageDescriptor withNavigator(ResolvedPageNavigatorDescriptor resolvedNavigator) {
        return new ResolvedModulePageDescriptor(template, explorer, resolvedNavigator, list, treeResource, detail, traits, quickSearchFields, actions, managedActions);
    }
}
