package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.ActionLogStatistics;
import net.ximatai.muyun.spring.ability.logging.BusinessLogCursor;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogCount;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogStatistics;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded in-process aggregation over the neutral cursor reader. It makes no authorization
 * decision and intentionally has no web endpoint; callers must authorize before invoking it.
 */
public final class StoreBackedBusinessLogStatisticsReader implements BusinessLogStatisticsReader {
    private static final int PAGE_SIZE = 200;
    private final BusinessLogStore store;

    public StoreBackedBusinessLogStatisticsReader(BusinessLogStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public ActionLogStatistics actionStatistics(BusinessLogStatisticsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        ActionAccumulator accumulator = new ActionAccumulator();
        boolean complete = read(query, event -> {
            if (event instanceof ActionLogEvent action) {
                accumulator.add(action);
            }
        });
        return accumulator.result(complete);
    }

    @Override
    public PageAccessLogStatistics pageAccessStatistics(BusinessLogStatisticsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Map<String, Long> counts = new LinkedHashMap<>();
        long[] total = {0};
        boolean complete = read(query, event -> {
            if (event instanceof PageAccessLogEvent access) {
                total[0]++;
                counts.merge(access.details().pageKey(), 1L, Long::sum);
            }
        });
        return new PageAccessLogStatistics(total[0], counts.entrySet().stream()
                .map(entry -> new PageAccessLogCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(PageAccessLogCount::accessCount).reversed()
                        .thenComparing(PageAccessLogCount::pageKey))
                .toList(), complete);
    }

    private boolean read(BusinessLogStatisticsQuery query, java.util.function.Consumer<BusinessLogEvent> consumer) {
        BusinessLogCursor cursor = null;
        int scanned = 0;
        while (scanned < query.maximumEvents()) {
            int limit = Math.min(PAGE_SIZE, query.maximumEvents() - scanned);
            var page = store.read(new BusinessLogQuery(query.occurredFrom(), query.occurredTo(), query.tenantId(),
                    query.moduleAlias(), query.actionCode(), null, cursor, limit));
            page.events().forEach(consumer);
            scanned += page.events().size();
            cursor = page.nextCursor();
            if (cursor == null) {
                return true;
            }
            if (page.events().isEmpty()) {
                return false;
            }
        }
        return false;
    }

    private static final class ActionAccumulator {
        private long executions;
        private long successes;
        private long failures;
        private long rejected;
        private long knownDurations;
        private long durationTotal;
        private long knownAffected;
        private long affectedTotal;

        private void add(ActionLogEvent event) {
            executions++;
            switch (event.details().outcome()) {
                case SUCCESS -> successes++;
                case FAILURE -> failures++;
                case REJECTED -> rejected++;
            }
            if (event.details().durationMillis() != null) {
                knownDurations++;
                durationTotal += event.details().durationMillis();
            }
            if (event.details().affectedRecordCount() != null) {
                knownAffected++;
                affectedTotal += event.details().affectedRecordCount();
            }
        }

        private ActionLogStatistics result(boolean complete) {
            return new ActionLogStatistics(executions, successes, failures, rejected, knownDurations,
                    durationTotal, knownAffected, affectedTotal, complete);
        }
    }
}
