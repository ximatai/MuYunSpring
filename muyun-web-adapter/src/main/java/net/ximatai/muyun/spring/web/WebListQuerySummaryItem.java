package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.util.Preconditions;

/**
 * One declared fact about the complete result set of a list query.
 *
 * <p>The value deliberately remains a JSON scalar or small structured value.  Presentation is
 * owned by the compiled page UI descriptor, while a summary implementation owns its calculation.</p>
 */
public record WebListQuerySummaryItem(String key, Object value) {
    public WebListQuerySummaryItem {
        key = Preconditions.requireText(key, "list query summary key");
    }
}
