package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
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
    private static final ActionExecutionPolicy CANDIDATE_READ_POLICY = new ActionExecutionPolicy(
            "accountRoleGrants",
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );
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

    public WebPageResponse<UserSelectorItem> query(String keyword, WebPageRequest page) {
        if (projectionService == null) {
            throw new IllegalStateException("user selector projection is not available");
        }
        Criteria criteria = Criteria.of().eq("enabled", Boolean.TRUE);
        if (keyword != null && !keyword.isBlank()) {
            criteria.andGroup(Criteria.of().orGroup(Criteria.of().like("username", keyword.trim()).getRoot()).getRoot());
        }
        WebPageRequest normalizedPage = page == null ? WebPageRequest.DEFAULT : page;
        PageRequest pageRequest = PageRequest.of(normalizedPage.pageNum(), normalizedPage.pageSize());
        if (userAccountService instanceof DataScopeAbility<?>) {
            DataScopeAbility<UserAccount> dataScopeAbility = DataScopeAbility.cast(userAccountService);
            DataScopeCriteriaResult scope = dataScopeAbility.readScopeByPolicy(CANDIDATE_READ_POLICY, criteria);
            return dataScopeAbility.withDataScopeTenant(scope, () -> project(scope.criteria(), pageRequest));
        }
        return project(criteria, pageRequest);
    }

    private WebPageResponse<UserSelectorItem> project(Criteria criteria, PageRequest pageRequest) {
        return projectionService.queryExplicitList(
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
