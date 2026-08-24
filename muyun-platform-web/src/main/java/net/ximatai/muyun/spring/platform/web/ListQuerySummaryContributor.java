package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebQueryRequest;

/**
 * Pluggable domain aggregate for one declared list-query footer summary.
 *
 * <p>The platform owns the request boundary and result-set scope. Contributors only add a
 * domain metric through the supplied scoped operations, so they cannot accidentally calculate
 * against a different tenant or bypass the current list filters.</p>
 */
public interface ListQuerySummaryContributor {
    /** Stable module scope of this contribution. It is registered during application wiring. */
    String moduleAlias();

    /** Stable domain metric key within {@link #moduleAlias()}. */
    String contributorKey();

    WebListQuerySummaryItem summarize(ListQuerySummaryContext context);

    interface ListQuerySummaryContext {
        String moduleAlias();
        String summaryKey();
        String contributorKey();
        WebQueryRequest request();
        long matchedTotal();
        /** Counts the current effective query additionally constrained by {@code criteria}. */
        long count(Criteria criteria);
        /** Aggregates the current effective query through its source-specific, scope-safe adapter. */
        java.util.List<java.util.Map<String, Object>> aggregate(AggregateQuery query);
    }
}
