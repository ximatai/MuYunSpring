package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageResult;
import net.ximatai.muyun.spring.web.WebPageResponse;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects cursor-backed administrative log pages onto the standard Web page envelope. */
public final class BusinessLogWebPageResponses {
    private BusinessLogWebPageResponses() {
    }

    public static WebPageResponse<BusinessLogEventResponse> from(BusinessLogPageResult page,
                                                                   BusinessLogOperatorIdentityLookup identityLookup) {
        Objects.requireNonNull(page, "page must not be null");
        List<BusinessLogEvent> events = page.events();
        List<BusinessLogOperatorIdentityKey> keys = events.stream()
                .map(BusinessLogEventResponse::identityKey)
                .flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream().toList();
        Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> identities = identityLookup == null
                ? Map.of() : identityLookup.resolve(keys);
        return new WebPageResponse<>(events.stream()
                .map(event -> BusinessLogEventResponse.from(event, identities)).toList(),
                page.total(), page.pageNum(), page.pageSize(),
                page.totalKnown() ? pages(page.total(), page.pageSize()) : 0,
                page.totalKnown(), null, List.of());
    }

    private static long pages(long total, int pageSize) {
        return total == 0 ? 0 : (total + pageSize - 1) / pageSize;
    }
}
