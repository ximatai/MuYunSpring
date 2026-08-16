package net.ximatai.muyun.spring.platform.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Intersects independently contributed visibility decisions and removes levels
 * whose required upstream navigator was rejected.  This keeps IAM, data scope
 * and business policies additive rather than letting the latest bean replace
 * earlier policy.
 */
public final class CompositePageNavigatorResolver implements PageNavigatorResolver {
    private final List<PageNavigatorResolver> delegates;

    public CompositePageNavigatorResolver(List<PageNavigatorResolver> delegates) {
        this.delegates = delegates == null ? List.of() : delegates.stream()
                .filter(delegate -> delegate != null && !(delegate instanceof CompositePageNavigatorResolver))
                .toList();
    }

    @Override
    public Set<String> visibleLevelKeys(PageNavigatorResolutionContext context) {
        if (context.candidate().navigator() == null) return Set.of();
        Set<String> visible = new LinkedHashSet<>(new DeclaredPageNavigatorResolver().visibleLevelKeys(context));
        for (PageNavigatorResolver delegate : delegates) {
            Set<String> allowed = delegate.visibleLevelKeys(context);
            visible.retainAll(allowed == null ? Set.of() : allowed);
        }
        removeUnavailableDescendants(context.candidate().navigator(), visible);
        return Set.copyOf(visible);
    }

    private void removeUnavailableDescendants(ResolvedPageNavigatorDescriptor navigator, Set<String> visible) {
        boolean changed;
        do {
            changed = false;
            for (ResolvedPageContextBindingDescriptor binding : navigator.contextBindings()) {
                if (binding.source() != PageContextSource.NAVIGATOR
                        || binding.target() != PageContextTarget.NAVIGATOR_QUERY
                        || !visible.contains(binding.targetNavigatorLevelKey())
                        || visible.contains(binding.sourceKey())) {
                    continue;
                }
                changed |= visible.remove(binding.targetNavigatorLevelKey());
            }
        } while (changed);
    }
}
