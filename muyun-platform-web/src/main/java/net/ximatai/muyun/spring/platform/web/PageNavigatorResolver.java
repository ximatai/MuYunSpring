package net.ximatai.muyun.spring.platform.web;

import java.util.Set;

/**
 * Resolves page-declared navigator candidates for the current request.
 *
 * <p>The resolver is the sole boundary for deciding which declared navigator levels are visible
 * for the request. It cannot rewrite a page template, source module, query binding, or child
 * relationship; those facts remain compiler-owned.</p>
 */
public interface PageNavigatorResolver {
    Set<String> visibleLevelKeys(PageNavigatorResolutionContext context);
}
