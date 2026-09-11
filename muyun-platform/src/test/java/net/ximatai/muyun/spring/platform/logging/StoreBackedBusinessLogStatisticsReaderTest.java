package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogCursor;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoreBackedBusinessLogStatisticsReaderTest {
    @Test
    void shouldAggregateKnownActionMetricsWithoutTreatingUnknownAsZero() {
        StoreBackedBusinessLogStatisticsReader reader = new StoreBackedBusinessLogStatisticsReader(new FixedStore(List.of(
                action("action-1", ActionLogDetails.ActionOutcome.SUCCESS, 12L, 3L),
                action("action-2", ActionLogDetails.ActionOutcome.FAILURE, null, null),
                action("action-3", ActionLogDetails.ActionOutcome.REJECTED, 5L, null)
        )));

        var result = reader.actionStatistics(BusinessLogStatisticsQuery.recent(10));

        assertThat(result.executionCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.knownDurationCount()).isEqualTo(2);
        assertThat(result.totalDurationMillis()).isEqualTo(17);
        assertThat(result.knownAffectedRecordCount()).isEqualTo(1);
        assertThat(result.totalAffectedRecordCount()).isEqualTo(3);
        assertThat(result.complete()).isTrue();
    }

    @Test
    void shouldAggregatePageKeysAndExposeWhenBoundedReadIsIncomplete() {
        StoreBackedBusinessLogStatisticsReader reader = new StoreBackedBusinessLogStatisticsReader(new FixedStore(List.of(
                page("page-1", "sales.contract:LIST"), page("page-2", "sales.contract:LIST"),
                page("page-3", "sales.contract:DETAIL")
        )));

        var result = reader.pageAccessStatistics(BusinessLogStatisticsQuery.recent(2));

        assertThat(result.accessCount()).isEqualTo(2);
        assertThat(result.pages()).extracting(value -> value.pageKey() + ":" + value.accessCount())
                .containsExactly("sales.contract:LIST:2");
        assertThat(result.complete()).isFalse();
    }

    private ActionLogEvent action(String id, ActionLogDetails.ActionOutcome outcome, Long duration, Long affected) {
        return new ActionLogEvent(context(id), new ActionLogDetails(outcome, "SERVICE", duration, affected, null, null));
    }

    private PageAccessLogEvent page(String id, String pageKey) {
        return new PageAccessLogEvent(context(id), new PageAccessLogDetails(pageKey, null, "menu", "MENU_BOOTSTRAP"));
    }

    private BusinessLogContext context(String eventId) {
        return new BusinessLogContext(eventId, Instant.parse("2026-09-11T00:00:00Z"), Instant.now(),
                "trace", "tenant", "user", "sales.contract", "submit");
    }

    private static final class FixedStore implements BusinessLogStore {
        private final List<BusinessLogEvent> events;
        private FixedStore(List<BusinessLogEvent> events) { this.events = events; }
        @Override public BusinessLogWriteResult append(BusinessLogEvent event) { throw new UnsupportedOperationException(); }
        @Override public List<BusinessLogWriteResult> appendAll(Collection<? extends BusinessLogEvent> events) { throw new UnsupportedOperationException(); }
        @Override public BusinessLogReadPage read(BusinessLogQuery query) {
            int start = query.cursor() == null ? 0 : Integer.parseInt(query.cursor().eventId());
            int end = Math.min(events.size(), start + query.limit());
            BusinessLogCursor next = end < events.size()
                    ? new BusinessLogCursor(Instant.parse("2026-09-11T00:00:00Z"), String.valueOf(end)) : null;
            return new BusinessLogReadPage(events.subList(start, end), next);
        }
    }
}
