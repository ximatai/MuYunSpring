package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidateQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.web.WebPageRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** HTTP request for one log stream's operator selector. */
public record BusinessLogOperatorCandidateRequest(String keyword,
                                                  List<String> selectedIds,
                                                  WebPageRequest page) {
    public static final BusinessLogOperatorCandidateRequest EMPTY =
            new BusinessLogOperatorCandidateRequest(null, List.of(), null);

    public BusinessLogOperatorCandidateQuery browseQuery() {
        return BusinessLogOperatorCandidateQuery.browse(keyword, pageRequest());
    }

    public BusinessLogOperatorCandidateQuery selectedQuery() {
        return BusinessLogOperatorCandidateQuery.selected(selectedOperatorIds());
    }

    public boolean hasSelectedIds() {
        return !selectedOperatorIds().isEmpty();
    }

    BusinessLogPageRequest pageRequest() {
        WebPageRequest normalized = page == null ? WebPageRequest.DEFAULT : page;
        return new BusinessLogPageRequest(normalized.pageNum(), normalized.pageSize());
    }

    private Set<String> selectedOperatorIds() {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(selectedIds);
    }
}
