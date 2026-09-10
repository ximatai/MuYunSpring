package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.AggregateOperation;
import net.ximatai.muyun.database.core.orm.AggregateSelection;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;

/** Executes compiled list footer summaries without owning a particular record source. */
@Component
public class ListQuerySummaryRuntime {
    private final ListQuerySummaryContributorCatalog contributorCatalog;
    private final ListQuerySummaryGroupLabelResolver groupLabels;

    public ListQuerySummaryRuntime(List<ListQuerySummaryContributor> contributors) {
        this(new ListQuerySummaryContributorCatalog(contributors), (moduleAlias, summary, values) -> Map.of());
    }

    public ListQuerySummaryRuntime(ListQuerySummaryContributorCatalog contributorCatalog) {
        this(contributorCatalog, (moduleAlias, summary, values) -> Map.of());
    }

    public ListQuerySummaryRuntime(ListQuerySummaryContributorCatalog contributorCatalog,
                                   ListQuerySummaryGroupLabelResolver groupLabels) {
        this.contributorCatalog = contributorCatalog == null
                ? new ListQuerySummaryContributorCatalog(List.of()) : contributorCatalog;
        this.groupLabels = groupLabels == null ? (moduleAlias, summary, values) -> Map.of() : groupLabels;
    }

    @Autowired
    public ListQuerySummaryRuntime(ListQuerySummaryContributorCatalog contributorCatalog,
                                   ObjectProvider<ListQuerySummaryGroupLabelResolver> groupLabels) {
        this.contributorCatalog = contributorCatalog == null ? new ListQuerySummaryContributorCatalog(List.of()) : contributorCatalog;
        this.groupLabels = (moduleAlias, summary, values) -> {
            ListQuerySummaryGroupLabelResolver resolver = groupLabels == null ? null : groupLabels.getIfAvailable();
            if (resolver == null) throw new UnsupportedOperationException(
                    "grouped list query summary label resolver is not available");
            return resolver.labels(moduleAlias, summary, values);
        };
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
            case SUM -> new WebListQuerySummaryItem(item.key(), sum(item, scopedAggregate));
            case GROUPED -> new WebListQuerySummaryItem(item.key(), grouped(moduleAlias, item, scopedAggregate));
            case CONTRIBUTOR -> contributor(moduleAlias, item.contributorKey()).summarize(context(
                    moduleAlias, item, request, matchedTotal, scopedCount, scopedAggregate));
        }).toList();
    }

    private WebGroupedListQuerySummaryValue grouped(String moduleAlias,
                                                     ResolvedPageListQuerySummaryDescriptor item,
                                                     ScopedAggregate scopedAggregate) {
        if (scopedAggregate == null) throw new UnsupportedOperationException(
                "grouped list query summary is not available for this record source");
        String countKey = item.key() + "__count";
        String sumKey = item.key() + "__sum";
        List<AggregateSelection> selections = new java.util.ArrayList<>();
        selections.add(AggregateSelection.count(countKey));
        if (item.fieldName() != null) selections.add(AggregateSelection.of(sumKey, AggregateOperation.SUM, item.fieldName()));
        List<Map<String, Object>> aggregates = scopedAggregate.aggregate(AggregateQuery.groupBy(
                List.of(item.groupByField()), selections));
        List<Map<String, Object>> rows = aggregates == null ? List.of() : aggregates;
        List<Object> values = rows.stream().map(row -> row.get(item.groupByField())).filter(java.util.Objects::nonNull).toList();
        Map<String, String> labels = groupLabels.labels(moduleAlias, item, values);
        return new WebGroupedListQuerySummaryValue(WebGroupedListQuerySummaryValue.KIND, rows.stream()
                .map(row -> groupedRow(item, row, countKey, sumKey, labels))
                .sorted(Comparator.comparing((WebGroupedListQuerySummaryValue.Row row) -> row.value() == null)
                        .thenComparing(WebGroupedListQuerySummaryValue.Row::label)
                        .thenComparing(row -> row.value() == null ? "" : String.valueOf(row.value())))
                .toList());
    }

    private static WebGroupedListQuerySummaryValue.Row groupedRow(ResolvedPageListQuerySummaryDescriptor item,
                                                                    Map<String, Object> row, String countKey,
                                                                    String sumKey, Map<String, String> labels) {
        Object value = row.get(item.groupByField());
        String label = value == null ? "未填写" : labels.get(String.valueOf(value));
        long count = row.get(countKey) instanceof Number number ? number.longValue() : 0L;
        BigDecimal sum = item.fieldName() == null ? null : decimal(row.get(sumKey));
        return new WebGroupedListQuerySummaryValue.Row(value, label, count, sum);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return new BigDecimal(String.valueOf(value));
    }

    private static Object sum(ResolvedPageListQuerySummaryDescriptor item, ScopedAggregate scopedAggregate) {
        if (scopedAggregate == null) throw new UnsupportedOperationException(
                "sum list query summary is not available for this record source");
        List<Map<String, Object>> rows = scopedAggregate.aggregate(AggregateQuery.of(List.of(
                AggregateSelection.of(item.key(), AggregateOperation.SUM, item.fieldName()))));
        if (rows == null || rows.isEmpty()) return BigDecimal.ZERO;
        Object value = rows.getFirst().get(item.key());
        return value == null ? BigDecimal.ZERO : value;
    }

    private ListQuerySummaryContributor contributor(String moduleAlias, String contributorKey) {
        return contributorCatalog.require(moduleAlias, contributorKey);
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
