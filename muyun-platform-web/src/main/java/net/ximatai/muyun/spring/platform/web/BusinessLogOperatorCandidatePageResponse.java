package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Standard candidate page plus authorized selected values needed to restore a persisted filter. */
public record BusinessLogOperatorCandidatePageResponse(
        List<BusinessLogOperatorCandidateResponse> records,
        List<BusinessLogOperatorCandidateResponse> selectedRecords,
        long total,
        int pageNum,
        int pageSize,
        long pages,
        boolean totalKnown
) {
    public BusinessLogOperatorCandidatePageResponse {
        records = records == null ? List.of() : List.copyOf(records);
        selectedRecords = selectedRecords == null ? List.of() : List.copyOf(selectedRecords);
    }
}
