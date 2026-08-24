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
}
