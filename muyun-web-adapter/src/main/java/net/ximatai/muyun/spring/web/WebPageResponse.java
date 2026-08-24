package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.ximatai.muyun.database.core.orm.PageResult;

import java.util.List;

public record WebPageResponse<T>(List<T> records,
                                 long total,
                                 int pageNum,
                                 int pageSize,
                                 long pages,
                                 boolean totalKnown,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Object navigation,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebListQuerySummaryItem> summaries) {
    /** Source-compatible constructor for ordinary list responses without query summaries. */
    public WebPageResponse(List<T> records, long total, int pageNum, int pageSize, long pages,
                           boolean totalKnown, Object navigation) {
        this(records, total, pageNum, pageSize, pages, totalKnown, navigation, List.of());
    }

    public WebPageResponse {
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }
    public static <T> WebPageResponse<T> from(PageResult<T> page) {
        return from(page, null);
    }

    public static <T> WebPageResponse<T> from(PageResult<T> page, Object navigation) {
        return new WebPageResponse<>(
                page.getRecords(),
                page.getTotal(),
                page.getPageNum(),
                page.getPageSize(),
                page.getPages(),
                page.isTotalKnown(),
                navigation,
                List.of()
        );
    }

    public static <T> WebPageResponse<T> fromList(List<T> records) {
        return fromList(records, null);
    }

    public static <T> WebPageResponse<T> fromList(List<T> records, Object navigation) {
        List<T> safeRecords = records == null ? List.of() : List.copyOf(records);
        return new WebPageResponse<>(
                safeRecords,
                safeRecords.size(),
                1,
                safeRecords.size(),
                safeRecords.isEmpty() ? 0 : 1,
                true,
                navigation,
                List.of()
        );
    }

    public WebPageResponse<T> withSummaries(List<WebListQuerySummaryItem> summaries) {
        return new WebPageResponse<>(records, total, pageNum, pageSize, pages, totalKnown, navigation, summaries);
    }
}
