package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.query.QueryLikePattern;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * User-account projection for role binding. This is deliberately not an HTTP endpoint:
 * {@link RoleWebController} owns the record-action authorization and authoritative tenant
 * context before it invokes the query.
 */
@Component
public class RoleAccountCandidateQueryService {
    private static final List<String> OUTPUT_FIELDS = List.of(
            "id", "username", "employeeId", "employeeNo", "employeeTitle",
            "employeeOrganizationId", "organizationTitle", "employeeDepartmentId", "departmentTitle"
    );

    private final UserAccountService userAccountService;
    private StaticRecordReadProjectionService projectionService;

    public RoleAccountCandidateQueryService(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Autowired(required = false)
    void setProjectionService(StaticRecordReadProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    public WebPageResponse<UserSelectorItem> query(String tenantId, String keyword, WebPageRequest page) {
        if (projectionService == null) {
            throw new IllegalStateException("user selector projection is not available");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("candidate tenantId is required");
        }
        Criteria criteria = Criteria.of().eq("tenantId", tenantId).eq("enabled", Boolean.TRUE);
        if (keyword != null && !keyword.isBlank()) {
            criteria.andGroup(Criteria.of().orGroup(Criteria.of().like("username", QueryLikePattern.containsLiteral(keyword.trim())).getRoot()).getRoot());
        }
        WebPageRequest normalizedPage = page == null ? WebPageRequest.DEFAULT : page;
        PageRequest pageRequest = PageRequest.of(normalizedPage.pageNum(), normalizedPage.pageSize());
        return project(criteria, pageRequest);
    }

    private WebPageResponse<UserSelectorItem> project(Criteria criteria, PageRequest pageRequest) {
        return projectionService.queryAuthorizedCandidates(
                        UserAccountService.MODULE_ALIAS,
                        "role_account_candidates",
                        OUTPUT_FIELDS,
                        userAccountService.activeCriteria(criteria),
                        pageRequest,
                        userAccountService,
                        Sort.asc("username"))
                .map(this::items)
                .orElseThrow(() -> new IllegalStateException("user selector projection is not available"));
    }

    private WebPageResponse<UserSelectorItem> items(
            WebPageResponse<Map<String, Object>> response) {
        return new WebPageResponse<>(
                response.records().stream().map(UserSelectorItem::from).toList(),
                response.total(), response.pageNum(), response.pageSize(), response.pages(), response.totalKnown(),
                response.navigation());
    }
}
