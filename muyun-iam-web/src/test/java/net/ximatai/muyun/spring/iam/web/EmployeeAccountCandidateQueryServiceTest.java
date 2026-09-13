package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeAccountCandidateQueryServiceTest {
    @Mock
    private UserAccountService userAccountService;
    @Captor
    private ArgumentCaptor<Criteria> criteriaCaptor;

    @Test
    void shouldKeepEligibleAccountFilteringInThePagedDatabaseQuery() {
        EmployeeAccountCandidateQueryService service = new EmployeeAccountCandidateQueryService(userAccountService);
        when(userAccountService.readScopeByPolicy(any(), any(Criteria.class)))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(1)));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<WebPageResponse<UserSelectorItem>> action = invocation.getArgument(1);
            return action.get();
        }).when(userAccountService).withDataScopeTenant(any(), any());
        when(userAccountService.activeCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccount user = new UserAccount();
        user.setId("user-1");
        user.setUsername("alice");
        when(userAccountService.pageQuery(criteriaCaptor.capture(), any(PageRequest.class), any()))
                .thenReturn(PageResult.of(List.of(user), 1, PageRequest.of(1, 20)));

        WebPageResponse<UserSelectorItem> response = service.query(
                "tenant-a", "alice", List.of("user-1"), WebPageRequest.DEFAULT);

        assertThat(response.records()).extracting(UserSelectorItem::username).containsExactly("alice");
        assertThat(criteriaCaptor.getValue().getClauses())
                .extracting(CriteriaClause::getField)
                .contains("tenantId", "enabled", "id");
        assertThat(criteriaCaptor.getValue().getClauses())
                .filteredOn(clause -> "username".equals(clause.getField()))
                .singleElement()
                .extracting(clause -> clause.getValues().getFirst())
                .isEqualTo("%alice%");
        assertThat(criteriaCaptor.getValue().getClauses())
                .filteredOn(clause -> clause.getOperator().name().equals("NOT_IN_SUBQUERY"))
                .singleElement()
                .extracting(clause -> (SqlSubQuery) clause.getValues().getFirst())
                .satisfies(subQuery -> {
                    assertThat(subQuery.getSql()).contains("iam_employee_account");
                    assertThat(subQuery.getParams()).containsEntry("tenantId", "tenant-a");
                });
    }

    @Test
    void shouldRejectAnUnboundedResolveIdListBeforeBuildingAnInPredicate() {
        EmployeeAccountCandidateQueryService service = new EmployeeAccountCandidateQueryService(userAccountService);

        assertThatThrownBy(() -> service.query("tenant-a", null,
                java.util.stream.IntStream.range(0, EmployeeAccountCandidateQueryService.MAXIMUM_RESOLVE_IDS + 1)
                        .mapToObj(index -> "user-" + index).toList(), WebPageRequest.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain more than "
                        + EmployeeAccountCandidateQueryService.MAXIMUM_RESOLVE_IDS);
    }

    @Test
    void shouldTreatWildcardCharactersInAccountSearchAsLiteralText() {
        EmployeeAccountCandidateQueryService service = new EmployeeAccountCandidateQueryService(userAccountService);
        when(userAccountService.readScopeByPolicy(any(), any(Criteria.class)))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(1)));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<WebPageResponse<UserSelectorItem>> action = invocation.getArgument(1);
            return action.get();
        }).when(userAccountService).withDataScopeTenant(any(), any());
        when(userAccountService.activeCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountService.pageQuery(criteriaCaptor.capture(), any(PageRequest.class), any()))
                .thenReturn(PageResult.of(List.of(), 0, PageRequest.of(1, 20)));

        service.query("tenant-a", "50%_", List.of(), WebPageRequest.DEFAULT);

        assertThat(criteriaCaptor.getValue().getClauses())
                .filteredOn(clause -> "username".equals(clause.getField()))
                .singleElement()
                .extracting(clause -> clause.getValues().getFirst())
                .isEqualTo("%50\\%\\_%");
    }

    @Test
    void shouldQuerySafeAccountProjectionInsideTheEmployeeRecordActionContext() {
        EmployeeAccountCandidateQueryService service = new EmployeeAccountCandidateQueryService(userAccountService);
        when(userAccountService.readScopeByPolicy(any(), any(Criteria.class)))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(1)));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<WebPageResponse<UserSelectorItem>> action = invocation.getArgument(1);
            return action.get();
        }).when(userAccountService).withDataScopeTenant(any(), any());
        when(userAccountService.activeCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccount user = new UserAccount();
        user.setId("user-1");
        user.setUsername("alice");
        when(userAccountService.pageQuery(any(Criteria.class), any(PageRequest.class), any()))
                .thenReturn(PageResult.of(List.of(user), 1, PageRequest.of(1, 20)));
        ActionExecutionContext employeeAction = ActionExecutionContext.ofPlatformAction(
                "iam.employee", PlatformAction.QUERY, Set.of("employee-1"),
                Optional.of(CurrentUser.tenantUser("operator-1", "Operator", "tenant-a")));

        try (ActionExecutionContextHolder.Scope ignored = ActionExecutionContextHolder.use(employeeAction)) {
            WebPageResponse<UserSelectorItem> response = service.query(
                    "tenant-a", "alice", List.of(), WebPageRequest.DEFAULT);

            assertThat(response.records()).extracting(UserSelectorItem::username).containsExactly("alice");
            assertThat(ActionExecutionContextHolder.current()).contains(employeeAction);
        }
    }
}
