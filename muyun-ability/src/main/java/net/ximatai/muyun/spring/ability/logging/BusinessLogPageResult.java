package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

/** A standard page reconstructed from cursor-backed business-log storage. */
public record BusinessLogPageResult(List<BusinessLogEvent> events,
                                    long total,
                                    int pageNum,
                                    int pageSize,
                                    boolean totalKnown) {
    public BusinessLogPageResult {
        events = events == null ? List.of() : List.copyOf(events);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (pageNum < 1 || pageSize < 1) {
            throw new IllegalArgumentException("pageNum and pageSize must be positive");
        }
    }
}
