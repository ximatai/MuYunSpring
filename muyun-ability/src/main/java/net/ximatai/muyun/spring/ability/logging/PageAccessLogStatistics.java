package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

/** Typed page-entry aggregation with an explicit bounded-read completeness indicator. */
public record PageAccessLogStatistics(long accessCount, List<PageAccessLogCount> pages, boolean complete) {
    public PageAccessLogStatistics {
        if (accessCount < 0) {
            throw new IllegalArgumentException("accessCount must not be negative");
        }
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
