package net.ximatai.muyun.spring.platform.web;

import java.util.Set;

/**
 * Baseline DSR: exposes declared candidates unchanged.
 *
 * <p>IAM data-scope integration replaces this decision without changing descriptor consumers.
 * Authorization is still enforced by the record and reference query paths.</p>
 */
public class DeclaredPageNavigatorResolver implements PageNavigatorResolver {
    @Override
    public Set<String> visibleLevelKeys(PageNavigatorResolutionContext context) {
        if (context.candidate().navigator() == null) {
            return Set.of();
        }
        return context.candidate().navigator().levels().stream()
                .map(ResolvedPageNavigatorLevelDescriptor::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
