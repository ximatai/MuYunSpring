package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.query.QueryLikePattern;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Candidate delivery for the employee-account binding action.
 *
 * <p>The employee action owns its authorization and target tenant before this service runs. The
 * candidate query therefore never exposes a generic user-management query to the browser. It also
 * excludes accounts that are already bound in the same tenant in SQL, so paging remains meaningful
 * when a tenant has many employee-account bindings.</p>
 */
@Component
public class EmployeeAccountCandidateQueryService {
    /** A picker can only rehydrate one bounded page of persisted selections. */
    static final int MAXIMUM_RESOLVE_IDS = 200;
    static final int MAXIMUM_KEYWORD_LENGTH = 128;
    private static final String BOUND_USER_IDS_SQL = """
            select user_id
            from iam_employee_account
            where tenant_id = :tenantId
            """;
    private static final ActionExecutionPolicy CANDIDATE_READ_POLICY = new ActionExecutionPolicy(
            "employeeAccounts",
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );
    private final UserAccountService userAccountService;

    public EmployeeAccountCandidateQueryService(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    public WebPageResponse<UserSelectorItem> query(String tenantId,
                                                    String keyword,
                                                    List<String> ids,
                                                    WebPageRequest page) {
        Criteria criteria = Criteria.of()
                .eq("tenantId", tenantId)
                .eq("enabled", Boolean.TRUE)
                .notInSubQuery("id", SqlSubQuery.of(BOUND_USER_IDS_SQL, Map.of("tenantId", tenantId)));
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword != null) {
            criteria.andGroup(Criteria.of().orGroup(Criteria.of()
                    .like("username", QueryLikePattern.containsLiteral(normalizedKeyword)).getRoot()).getRoot());
        }
        List<String> normalizedIds = normalizeIds(ids);
        if (!normalizedIds.isEmpty()) {
            criteria.in("id", normalizedIds);
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
        /*
         * This record action authorizes the employee binding, while the candidate set is a safe,
         * deliberately small user-account projection.  Do not send the outer iam.employee action
         * context into the iam.user read-projection planner: its alias check correctly rejects
         * that cross-module context.  The explicit data-scope policy above remains the sole
         * authorization input for this internal candidate read.
         */
        PageResult<UserAccount> page = userAccountService.pageQuery(
                userAccountService.activeCriteria(criteria), pageRequest, Sort.asc("username"));
        return new WebPageResponse<>(
                page.getRecords().stream().map(UserSelectorItem::from).toList(),
                page.getTotal(), page.getPageNum(), page.getPageSize(), page.getPages(), page.isTotalKnown(), null);
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > MAXIMUM_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("employee account candidate keyword must not exceed "
                    + MAXIMUM_KEYWORD_LENGTH + " characters");
        }
        return normalized;
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAXIMUM_RESOLVE_IDS) {
            throw new IllegalArgumentException("employee account candidate ids must not contain more than "
                    + MAXIMUM_RESOLVE_IDS + " values");
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
