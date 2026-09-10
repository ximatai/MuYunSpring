package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.AggregateOperation;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.AggregateSelection;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListQuerySummaryRuntimeTest {
    @Test
    void shouldDelegateDeclaredContributorToScopedAggregate() {
        ListQuerySummaryContributor contributor = new ListQuerySummaryContributor() {
            @Override public String moduleAlias() { return "sales.contract"; }
            @Override public String contributorKey() { return "contract.total"; }
            @Override public WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
                Object value = context.aggregate(AggregateQuery.of(List.of(
                        AggregateSelection.of("total", AggregateOperation.SUM, "amount"))))
                        .getFirst().get("total");
                return new WebListQuerySummaryItem(context.summaryKey(), value);
            }
        };
        ListQuerySummaryRuntime runtime = new ListQuerySummaryRuntime(List.of(contributor));

        List<WebListQuerySummaryItem> summaries = runtime.summarize("sales.contract", List.of(
                        new ResolvedPageListQuerySummaryDescriptor("amount", "金额",
                                PageListQuerySummaryDefinition.Source.CONTRIBUTOR, "contract.total")),
                null, 7, ignored -> 7,
                query -> List.of(Map.of("total", new BigDecimal("333.10"))));

        assertThat(summaries).containsExactly(new WebListQuerySummaryItem("amount", new BigDecimal("333.10")));
    }

    @Test
    void shouldSumThroughScopedAggregateAndNormalizeEmptyResultToZero() {
        ListQuerySummaryRuntime runtime = new ListQuerySummaryRuntime(List.of());

        assertThat(runtime.summarize("sales.contract", List.of(
                        new ResolvedPageListQuerySummaryDescriptor("amount", "金额",
                                PageListQuerySummaryDefinition.Source.SUM, "amount", null)),
                null, 0, ignored -> 0, query -> List.of()))
                .containsExactly(new WebListQuerySummaryItem("amount", BigDecimal.ZERO));
    }

    @Test
    void shouldAggregateGroupedCountAndOptionalSumIndependentlyOfListPagination() {
        ListQuerySummaryRuntime runtime = new ListQuerySummaryRuntime(new ListQuerySummaryContributorCatalog(List.of()),
                (moduleAlias, summary, values) -> Map.of("approved", "已通过", "draft", "草稿"));

        assertThat(runtime.summarize("sales.contract", List.of(
                        new ResolvedPageListQuerySummaryDescriptor("statusBreakdown", "状态汇总",
                                PageListQuerySummaryDefinition.Source.GROUPED, "amount", null,
                                "status", "状态", "金额")),
                null, 1, ignored -> 1, query -> {
                    assertThat(query.groupByFields()).containsExactly("status");
                    return List.of(Map.of("status", "draft", "statusBreakdown__count", 2L,
                                    "statusBreakdown__sum", new BigDecimal("19.50")),
                            Map.of("status", "approved", "statusBreakdown__count", 3L,
                                    "statusBreakdown__sum", new BigDecimal("80.50")),
                            Map.of("statusBreakdown__count", 1L, "statusBreakdown__sum", BigDecimal.ZERO));
                }))
                .containsExactly(new WebListQuerySummaryItem("statusBreakdown",
                        new WebGroupedListQuerySummaryValue("GROUPED", List.of(
                                new WebGroupedListQuerySummaryValue.Row("approved", "已通过", 3, new BigDecimal("80.50")),
                                new WebGroupedListQuerySummaryValue.Row("draft", "草稿", 2, new BigDecimal("19.50")),
                                new WebGroupedListQuerySummaryValue.Row(null, "未填写", 1, BigDecimal.ZERO)))));
    }
}
