package net.ximatai.muyun.spring.ability.logging;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded, storage-neutral request for distinct operator accounts in a visible log stream. */
public record BusinessLogOperatorCandidateQuery(String keyword,
                                                Set<String> operatorIds,
                                                String tenantId,
                                                String organizationId,
                                                String departmentId,
                                                BusinessLogPageRequest page) {
    public static final int MAXIMUM_SELECTED_IDS = 200;

    public BusinessLogOperatorCandidateQuery {
        keyword = BusinessLogContext.optional(keyword, "keyword", 128);
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        organizationId = BusinessLogContext.optional(organizationId, "organizationId", 128);
        departmentId = BusinessLogContext.optional(departmentId, "departmentId", 128);
        if (operatorIds != null) {
            if (operatorIds.size() > MAXIMUM_SELECTED_IDS) {
                throw new IllegalArgumentException("operatorIds must not contain more than " + MAXIMUM_SELECTED_IDS);
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String operatorId : operatorIds) {
                String value = BusinessLogContext.optional(operatorId, "operatorIds", 128);
                if (value != null) {
                    normalized.add(value);
                }
            }
            operatorIds = Set.copyOf(normalized);
        }
        page = page == null ? BusinessLogPageRequest.defaults() : page;
    }

    public static BusinessLogOperatorCandidateQuery browse(String keyword, BusinessLogPageRequest page) {
        return new BusinessLogOperatorCandidateQuery(keyword, null, null, null, null, page);
    }

    public static BusinessLogOperatorCandidateQuery selected(Set<String> operatorIds) {
        int size = operatorIds == null ? 0 : operatorIds.size();
        return new BusinessLogOperatorCandidateQuery(null, operatorIds, null, null, null,
                new BusinessLogPageRequest(1, size == 0 ? 1 : size));
    }

    public BusinessLogOperatorCandidateQuery(String keyword, Set<String> operatorIds,
                                             BusinessLogPageRequest page) {
        this(keyword, operatorIds, null, null, null, page);
    }

    public BusinessLogOperatorCandidateQuery withScope(String tenantId, String organizationId,
                                                        String departmentId) {
        return new BusinessLogOperatorCandidateQuery(keyword, operatorIds, tenantId, organizationId, departmentId, page);
    }
}
