package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Resolved form of one navigator level; DSR may omit levels not selectable for the current user. */
public record ResolvedPageNavigatorLevelDescriptor(String key,
                                                   PageNavigatorKind kind,
                                                   String sourceModuleAlias,
                                                   String title,
                                                   String searchPlaceholder,
                                                   List<ResolvedPageNavigatorQueryBindingDescriptor> queryBindings,
                                                   List<ResolvedPageNavigatorChildBindingDescriptor> childBindings,
                                                   ResolvedPageNavigatorManagementDescriptor management,
                                                   PageNavigatorSingleResultPolicy singleResultPolicy) {
    public ResolvedPageNavigatorLevelDescriptor {
        queryBindings = queryBindings == null ? List.of() : List.copyOf(queryBindings);
        childBindings = childBindings == null ? List.of() : List.copyOf(childBindings);
        singleResultPolicy = singleResultPolicy == null ? PageNavigatorSingleResultPolicy.NONE : singleResultPolicy;
    }

    static ResolvedPageNavigatorLevelDescriptor from(PageNavigatorLevelDefinition definition) {
        return new ResolvedPageNavigatorLevelDescriptor(definition.key(), definition.kind(), definition.sourceModuleAlias(),
                definition.title(), definition.searchPlaceholder(), definition.queryBindings().stream()
                .map(binding -> new ResolvedPageNavigatorQueryBindingDescriptor(binding.field(), binding.queryCriteriaKey()))
                .toList(), definition.childBindings().stream().map(binding ->
                new ResolvedPageNavigatorChildBindingDescriptor(binding.childLevelKey(), binding.childQueryCriteriaKey()))
                .toList(), ResolvedPageNavigatorManagementDescriptor.from(definition.management()),
                definition.singleResultPolicy());
    }
}
