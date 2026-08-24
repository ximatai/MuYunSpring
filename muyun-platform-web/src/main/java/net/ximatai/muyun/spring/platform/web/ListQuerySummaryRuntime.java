package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** Executes compiled list footer summaries without owning a particular record source. */
@Component
public class ListQuerySummaryRuntime {
    private final List<ListQuerySummaryContributor> contributors;

    public ListQuerySummaryRuntime(List<ListQuerySummaryContributor> contributors) {
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    public List<WebListQuerySummaryItem> summarize(String moduleAlias,
                                                    List<ResolvedPageListQuerySummaryDescriptor> descriptors,
                                                    WebQueryRequest request,
                                                    long matchedTotal,
                                                    ScopedCount scopedCount,
                                                    ScopedAggregate scopedAggregate) {
        if (descriptors == null || descriptors.isEmpty()) return List.of();
        if (scopedCount == null) throw new IllegalArgumentException("list query summary requires scoped count");
        return descriptors.stream().map(item -> switch (item.source()) {
            case MATCHED_COUNT -> new WebListQuerySummaryItem(item.key(), matchedTotal);
            case CONTRIBUTOR -> contributor(moduleAlias, item.contributorKey()).summarize(context(
                    moduleAlias, item, request, matchedTotal, scopedCount, scopedAggregate));
        }).toList();
    }

    private ListQuerySummaryContributor contributor(String moduleAlias, String contributorKey) {
        return contributors.stream().filter(candidate -> candidate.supports(moduleAlias, contributorKey))
                .reduce((left, right) -> { throw new IllegalStateException(
                        "multiple list query summary contributors: " + moduleAlias + "." + contributorKey); })
                .orElseThrow(() -> new IllegalStateException(
                        "no list query summary contributor: " + moduleAlias + "." + contributorKey));
    }

    private static ListQuerySummaryContributor.ListQuerySummaryContext context(
            String moduleAlias, ResolvedPageListQuerySummaryDescriptor item, WebQueryRequest request,
            long matchedTotal, ScopedCount scopedCount, ScopedAggregate scopedAggregate) {
        return new ListQuerySummaryContributor.ListQuerySummaryContext() {
            @Override public String moduleAlias() { return moduleAlias; }
            @Override public String summaryKey() { return item.key(); }
            @Override public String contributorKey() { return item.contributorKey(); }
            @Override public WebQueryRequest request() { return request; }
            @Override public long matchedTotal() { return matchedTotal; }
            @Override public long count(Criteria criteria) { return scopedCount.count(criteria); }
            @Override public java.util.List<java.util.Map<String, Object>> aggregate(AggregateQuery query) {
                if (scopedAggregate == null) throw new UnsupportedOperationException(
                        "aggregate list query summary is not available for this record source");
                return scopedAggregate.aggregate(query);
            }
        };
    }

    @FunctionalInterface
    public interface ScopedCount {
        long count(Criteria additionalCriteria);
    }

    @FunctionalInterface
    public interface ScopedAggregate {
        java.util.List<java.util.Map<String, Object>> aggregate(AggregateQuery query);
    }
}
