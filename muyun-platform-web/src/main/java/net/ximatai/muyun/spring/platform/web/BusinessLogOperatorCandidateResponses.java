package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidate;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidatePage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts log-sourced account IDs into safe selector labels without changing log authorization. */
public final class BusinessLogOperatorCandidateResponses {
    private BusinessLogOperatorCandidateResponses() {
    }

    public static BusinessLogOperatorCandidatePageResponse from(BusinessLogOperatorCandidatePage page,
                                                                 BusinessLogOperatorCandidatePage selected,
                                                                 BusinessLogOperatorIdentityLookup lookup) {
        return from(page, selected, lookup, true);
    }

    public static BusinessLogOperatorCandidatePageResponse from(BusinessLogOperatorCandidatePage page,
                                                                 BusinessLogOperatorCandidatePage selected,
                                                                 BusinessLogOperatorIdentityLookup lookup,
                                                                 boolean totalKnown) {
        Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> identities = lookup == null ? Map.of()
                : lookup.resolve(keys(page.candidates(), selected == null ? List.of() : selected.candidates()));
        return new BusinessLogOperatorCandidatePageResponse(items(page.candidates(), identities),
                selected == null ? List.of() : items(selected.candidates(), identities), page.total(), page.pageNum(),
                page.pageSize(), pages(page.total(), page.pageSize()), totalKnown);
    }

    private static List<BusinessLogOperatorIdentityKey> keys(Collection<BusinessLogOperatorCandidate> primary,
                                                               Collection<BusinessLogOperatorCandidate> selected) {
        return java.util.stream.Stream.concat(primary.stream(), selected.stream())
                .map(candidate -> new BusinessLogOperatorIdentityKey(candidate.tenantId(), candidate.operatorId(), null))
                .distinct().toList();
    }

    private static List<BusinessLogOperatorCandidateResponse> items(Collection<BusinessLogOperatorCandidate> candidates,
                                                                      Map<BusinessLogOperatorIdentityKey,
                                                                              BusinessLogOperatorIdentity> identities) {
        Map<String, BusinessLogOperatorCandidateResponse> distinct = new LinkedHashMap<>();
        for (BusinessLogOperatorCandidate candidate : candidates) {
            BusinessLogOperatorIdentity identity = identities.get(new BusinessLogOperatorIdentityKey(candidate.tenantId(),
                    candidate.operatorId(), null));
            distinct.putIfAbsent(candidate.operatorId(), new BusinessLogOperatorCandidateResponse(candidate.operatorId(),
                    label(candidate.operatorId(), candidate.operatorAccount(), identity), subtitle(identity)));
        }
        return List.copyOf(distinct.values());
    }

    private static String label(String operatorId, String operatorAccount, BusinessLogOperatorIdentity identity) {
        if (identity == null) {
            return operatorAccount == null ? operatorId : operatorAccount;
        }
        if (identity.employeeName() != null && identity.username() != null) {
            return identity.employeeName() + " (" + identity.username() + ")";
        }
        if (identity.employeeName() != null) {
            return identity.employeeName();
        }
        return identity.username() == null ? (operatorAccount == null ? operatorId : operatorAccount) : identity.username();
    }

    private static String subtitle(BusinessLogOperatorIdentity identity) {
        if (identity == null) {
            return null;
        }
        if (identity.organizationName() != null && identity.departmentName() != null) {
            return identity.organizationName() + " / " + identity.departmentName();
        }
        return identity.organizationName() == null ? identity.departmentName() : identity.organizationName();
    }

    private static long pages(long total, int pageSize) {
        return total == 0 ? 0 : (total + pageSize - 1) / pageSize;
    }
}
