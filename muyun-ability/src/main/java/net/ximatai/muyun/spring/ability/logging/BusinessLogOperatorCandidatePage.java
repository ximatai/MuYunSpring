package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

/** A standard offset page of distinct operator accounts sourced from authorized log facts. */
public record BusinessLogOperatorCandidatePage(List<BusinessLogOperatorCandidate> candidates,
                                                long total,
                                                int pageNum,
                                                int pageSize) {
    public BusinessLogOperatorCandidatePage {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (total < 0 || pageNum < 1 || pageSize < 1) {
            throw new IllegalArgumentException("candidate page values must be non-negative and paged");
        }
    }
}
