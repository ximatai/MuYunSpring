package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountServiceContractTest {
    private final PasswordHashingService passwordHashingService = new PasswordHashingService();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CurrentUserContext.clear();
        ActionExecutionContextHolder.clear();
    }

    @Test
    void shouldExposeDataScopeAbility() {
        UserAccountService service = userAccountServiceFixture(mock(UserAccountDao.class), tenantId -> {
                }, passwordHashingService).dataScope(mock(DataScopeCriteriaService.class)).build();

        assertThat(service).isInstanceOf(DataScopeAbility.class);
    }

    @Test
    void shouldExposeQuerySchemaForUserManagementScopes() {
        UserAccountService service = userAccountServiceFixture(mock(UserAccountDao.class), tenantId -> {
                }, passwordHashingService).dataScope(mock(DataScopeCriteriaService.class)).build();

        assertThat(service).isInstanceOf(QueryAbility.class);
        QuerySchema schema = service.querySchema();

        assertThat(schema.fields()).extracting(QuerySchema.Field::name)
                .contains("tenantId", "username", "enabled", "passwordStatus", "lastLoginAt")
                .doesNotContain("organizationId", "title", "mobile", "email", "sortOrder");
        assertThat(field(schema, "tenantId").operators())
                .containsExactly(QueryOperator.EQ, QueryOperator.IN, QueryOperator.NULL);
        assertThat(service.queryDescriptor().externalCriteriaKeys()).containsExactly("tenantId", "onlineOnly");
        assertThat(schema.quickSearch().fields()).containsExactly("username");
        assertThat(schema.defaultSorts()).extracting(QuerySchema.DefaultSort::field)
                .containsExactly("username");
    }

    @Test
    void shouldApplyOnlineOnlyAsAnExplicitServerSideQueryConstraint() {
        UserSessionPresenceService presenceService = mock(UserSessionPresenceService.class);
        when(presenceService.activeAccountCriteria()).thenReturn(Criteria.of().eq("id", "user-1"));
        UserAccountService service = userAccountServiceFixture(mock(UserAccountDao.class), tenantId -> {
                }, passwordHashingService).sessionPresence(presenceService).build();
        QueryRequest request = new QueryRequest(
                List.of(), null, java.util.Map.of(), List.of(), null, null,
                java.util.Map.of("onlineOnly", Boolean.TRUE), null, List.of(), false, null);

        assertThat(service.queryCriteria(request)).isNotNull();

        verify(presenceService).activeAccountCriteria();
    }

    @Test
    void shouldSyncUserAccountDataScopeFieldsOnInsert() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<UserAccount>getArgument(0).getId());
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserAccount user = new UserAccount();
        user.setUsername("alice");
        user.setTitle("Alice");
        user.setSortOrder(1);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.createUser(user, "secret1");
        }

        assertThat(user.getAuthUserId()).isEqualTo(user.getId());
        assertThat(user.getAuthOrganizationId()).isNull();
        assertThat(user.getAuthModuleAlias()).isEqualTo(UserAccountService.MODULE_ALIAS);
        assertThat(user.getTitle()).isEqualTo("alice");
        assertThat(user.getSortOrder()).isNull();
        assertThat(user.getPasswordStatus()).isEqualTo(PasswordStatus.INITIAL);
        assertThat(user.getPasswordChangedAt()).isNotNull();
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void shouldDefaultUserTitleFromUsernameWhenMissing() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<UserAccount>getArgument(0).getId());
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserAccount user = new UserAccount();
        user.setUsername("alice");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.createUser(user, "secret1");
        }

        assertThat(user.getTitle()).isEqualTo("alice");
    }

    @Test
    void shouldValidatePasswordPolicyWhenWritingUserPassword() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.insert(any())).thenAnswer(invocation -> {
            invocation.<UserAccount>getArgument(0).setId("user-2");
            return "user-2";
        });
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(
                List.of(),
                List.of(),
                List.of(user),
                List.of(user)
        );
        when(dao.updateById(any(UserAccount.class))).thenReturn(1);
        PasswordPolicyRuleService passwordPolicyRuleService = mock(PasswordPolicyRuleService.class);
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).passwordPolicy(passwordPolicyRuleService).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            UserAccount created = new UserAccount();
            created.setUsername("bob");
            service.createUser(created, "create1");
            service.changePassword("user-1", "admin2");
            service.changeOwnPassword("user-1", "admin2", "own3");
        }

        verify(passwordPolicyRuleService).validatePassword("create1");
        verify(passwordPolicyRuleService).validatePassword("admin2");
        verify(passwordPolicyRuleService).validatePassword("own3");
    }

    @Test
    void shouldRejectPasswordWhenPolicyRuleFails() {
        UserAccountDao dao = mock(UserAccountDao.class);
        PasswordPolicyRuleService passwordPolicyRuleService = mock(PasswordPolicyRuleService.class);
        org.mockito.Mockito.doThrow(new BusinessException("iam.user.password-policy-violated", "密码必须包含数字"))
                .when(passwordPolicyRuleService).validatePassword("secret");
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).passwordPolicy(passwordPolicyRuleService).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            UserAccount user = new UserAccount();
            user.setUsername("alice");
            assertThatThrownBy(() -> service.createUser(user, "secret"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("密码必须包含数字")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.user.password-policy-violated"));
        }

        verify(dao, never()).insert(any());
    }

    @Test
    void shouldPhysicallyDeleteUserAccount() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        user.setVersion(1);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.deleteByIdAndVersion("user-1", 1)).thenReturn(1);
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).dataScope(new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService()).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.delete("user-1")).isEqualTo(1);
        }

        verify(dao).deleteByIdAndVersion("user-1", 1);
        verify(dao, never()).updateByIdAndVersion(any(UserAccount.class), any());
    }

    @Test
    void shouldCleanupAccountRoleGrantsWhenPhysicallyDeletingUserAccount() {
        UserAccountDao dao = mock(UserAccountDao.class);
        AccountRoleGrantDao accountRoleGrantDao = mock(AccountRoleGrantDao.class);
        UserAccount user = activeUser();
        user.setVersion(1);
        AccountRoleGrant grant = new AccountRoleGrant();
        grant.setId("grant-1");
        grant.setUserId("user-1");
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.deleteByIdAndVersion("user-1", 1)).thenReturn(1);
        when(accountRoleGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(grant));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).dataScope(new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService()).roleGrants(accountRoleGrantDao).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.delete("user-1")).isEqualTo(1);
        }

        verify(accountRoleGrantDao).deleteById("grant-1");
    }

    @Test
    void shouldRepairAccountRoleGrantUsernameReferencesToUserIds() {
        UserAccountDao dao = mock(UserAccountDao.class);
        AccountRoleGrantDao accountRoleGrantDao = mock(AccountRoleGrantDao.class);
        UserAccount user = activeUser();
        AccountRoleGrant grant = accountRoleGrant("grant-1", "role-1", "alice");
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(accountRoleGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(grant));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).roleGrants(accountRoleGrantDao).build();

        UserAccountService.AccountRoleGrantUserIdRepairResult result = service.repairAccountRoleGrantUserIds();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.deletedDuplicates()).isZero();
        assertThat(grant.getUserId()).isEqualTo("user-1");
        verify(accountRoleGrantDao).updateById(grant);
    }

    @Test
    void shouldDeleteDuplicateUsernameAccountRoleGrantWhenRepairingUserIds() {
        UserAccountDao dao = mock(UserAccountDao.class);
        AccountRoleGrantDao accountRoleGrantDao = mock(AccountRoleGrantDao.class);
        UserAccount user = activeUser();
        AccountRoleGrant usernameGrant = accountRoleGrant("grant-username", "role-1", "alice");
        AccountRoleGrant userIdGrant = accountRoleGrant("grant-user-id", "role-1", "user-1");
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(accountRoleGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(usernameGrant, userIdGrant));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).roleGrants(accountRoleGrantDao).build();

        UserAccountService.AccountRoleGrantUserIdRepairResult result = service.repairAccountRoleGrantUserIds();

        assertThat(result.updated()).isZero();
        assertThat(result.deletedDuplicates()).isEqualTo(1);
        verify(accountRoleGrantDao).deleteById("grant-username");
        verify(accountRoleGrantDao, never()).updateById(any(AccountRoleGrant.class));
    }

    @Test
    void shouldPreserveEnabledUsernameGrantWhenRepairingDuplicateUserIdGrant() {
        UserAccountDao dao = mock(UserAccountDao.class);
        AccountRoleGrantDao accountRoleGrantDao = mock(AccountRoleGrantDao.class);
        UserAccount user = activeUser();
        AccountRoleGrant usernameGrant = accountRoleGrant("grant-username", "role-1", "alice");
        AccountRoleGrant userIdGrant = accountRoleGrant("grant-user-id", "role-1", "user-1");
        userIdGrant.setEnabled(Boolean.FALSE);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(accountRoleGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(usernameGrant, userIdGrant));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).roleGrants(accountRoleGrantDao).build();

        UserAccountService.AccountRoleGrantUserIdRepairResult result = service.repairAccountRoleGrantUserIds();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.deletedDuplicates()).isEqualTo(1);
        assertThat(userIdGrant.getEnabled()).isTrue();
        verify(accountRoleGrantDao).updateById(userIdGrant);
        verify(accountRoleGrantDao).deleteById("grant-username");
    }

    @Test
    void shouldApplyRecordDataScopeWhenChangingPassword() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.updateById(any(UserAccount.class))).thenReturn(1);
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(UserAccountService.MODULE_ALIAS),
                any(ActionExecutionPolicy.class), any(Criteria.class),
                org.mockito.ArgumentMatchers.<Optional<CurrentUser>>any(),
                any(DataScopeFieldMapping.class)))
                .thenReturn(DataScopeCriteriaResult.restricted(Criteria.of().eq("id", "user-1")));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).dataScope(dataScope).build();
        ActionExecutionPolicy policy = new ActionExecutionPolicy(
                "changePassword",
                PlatformActionLevel.RECORD,
                ActionAccessMode.AUTH_REQUIRED,
                true,
                true,
                ActionDefaultGrantPolicy.NONE,
                null
        );

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             ActionExecutionContextHolder.Scope ignoredAction = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPolicy(
                             UserAccountService.MODULE_ALIAS,
                             policy,
                             Set.of("user-1"),
                             Optional.empty()
                     ))) {
            assertThat(service.changePassword("user-1", "secret2")).isEqualTo(1);
        }

        assertThat(user.getPasswordStatus()).isEqualTo(PasswordStatus.NORMAL);
        assertThat(user.getPasswordExpiresAt()).isNull();

        verify(dataScope).resolveReadScope(
                eq(UserAccountService.MODULE_ALIAS),
                eq(policy),
                any(Criteria.class),
                org.mockito.ArgumentMatchers.<Optional<CurrentUser>>any(),
                any(DataScopeFieldMapping.class)
        );
    }

    @Test
    void shouldUseChangePasswordDataScopePolicyWithoutActionContext() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.updateById(any(UserAccount.class))).thenReturn(1);
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(UserAccountService.MODULE_ALIAS),
                any(ActionExecutionPolicy.class), any(Criteria.class),
                org.mockito.ArgumentMatchers.<Optional<CurrentUser>>any(),
                any(DataScopeFieldMapping.class)))
                .thenReturn(DataScopeCriteriaResult.restricted(Criteria.of().eq("id", "user-1")));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).dataScope(dataScope).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.changePassword("user-1", "secret2")).isEqualTo(1);
        }

        verify(dataScope).resolveReadScope(
                eq(UserAccountService.MODULE_ALIAS),
                org.mockito.ArgumentMatchers.<ActionExecutionPolicy>argThat(policy ->
                        "changePassword".equals(policy.actionCode()) && policy.requiresDataScope()),
                any(Criteria.class),
                org.mockito.ArgumentMatchers.<Optional<CurrentUser>>any(),
                any(DataScopeFieldMapping.class)
        );
    }

    @Test
    void shouldRejectChangePasswordWhenRecordDataScopeDenied() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.count(any(Criteria.class))).thenReturn(0L);
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(UserAccountService.MODULE_ALIAS),
                any(ActionExecutionPolicy.class), any(Criteria.class),
                org.mockito.ArgumentMatchers.<Optional<CurrentUser>>any(),
                any(DataScopeFieldMapping.class)))
                .thenReturn(DataScopeCriteriaResult.restricted(Criteria.of().eq("id", "user-1")));
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).dataScope(dataScope).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.changePassword("user-1", "secret2"))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                    .hasMessageContaining("record data permission denied");
        }

        verify(dao, never()).updateById(any(UserAccount.class));
    }

    @Test
    void shouldResetPasswordWithTemporaryPasswordAndRequiredStatus() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.updateById(any(UserAccount.class))).thenReturn(1);
        PasswordPolicyRuleService passwordPolicyRuleService = mock(PasswordPolicyRuleService.class);
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).passwordPolicy(passwordPolicyRuleService).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            UserAccountService.PasswordResetResult result = service.resetPassword("user-1");

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.temporaryPassword()).isNotBlank();
            assertThat(result.expiresAt()).isNotNull();
            assertThat(passwordHashingService.matches(result.temporaryPassword(), user.getPasswordHash())).isTrue();
            assertThat(user.getPasswordStatus()).isEqualTo(PasswordStatus.RESET_REQUIRED);
            assertThat(user.getPasswordExpiresAt()).isEqualTo(result.expiresAt());
        }

        verify(passwordPolicyRuleService, atLeastOnce()).validatePassword(any());
    }

    @Test
    void shouldPublishSecurityEventsWhenPasswordChanges() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        when(dao.updateById(any(UserAccount.class))).thenReturn(1);
        RecordingUserSecurityEventPublisher eventPublisher = new RecordingUserSecurityEventPublisher();
        UserSessionRevocationService revocationService = mock(UserSessionRevocationService.class);
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).securityEvents(eventPublisher).sessionRevocation(revocationService).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.changePassword("user-1", "secret2");
            UserAccountService.PasswordResetResult resetResult = service.resetPassword("user-1");
            service.changeOwnPassword("user-1", resetResult.temporaryPassword(), "secret3");
        }

        assertThat(eventPublisher.events).containsExactly(
                UserSecurityEvent.passwordChanged("user-1"),
                UserSecurityEvent.passwordReset("user-1"),
                UserSecurityEvent.passwordChanged("user-1")
        );
        verify(revocationService).revokeUserSessions("user-1", "password changed");
        verify(revocationService).revokeUserSessions("user-1", "password reset");
        verify(revocationService).revokeUserSessions("user-1", "own password changed");
    }

    @Test
    void shouldPublishSecurityEventWhenForceLogout() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = activeUser();
        when(dao.count(any(Criteria.class))).thenReturn(1L);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        RecordingUserSecurityEventPublisher eventPublisher = new RecordingUserSecurityEventPublisher();
        UserSessionRevocationService revocationService = mock(UserSessionRevocationService.class);
        when(revocationService.revokeUserSessions("user-1", "force logout")).thenReturn(2);
        UserAccountService service = userAccountServiceFixture(dao, tenantId -> {
        }, passwordHashingService).securityEvents(eventPublisher).sessionRevocation(revocationService).build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.forceLogout("user-1")).isEqualTo(2);
        }

        assertThat(eventPublisher.events).containsExactly(UserSecurityEvent.forceLogout("user-1"));
        verify(revocationService).revokeUserSessions("user-1", "force logout");
        verify(dao, never()).updateById(any(UserAccount.class));
    }

    @Test
    void shouldRejectCurrentUserPasswordAdministration() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "Alice", "tenant-a"))) {
            assertThatThrownBy(() -> service.changePassword("user-1", "secret2"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("不能由管理员重置当前登录用户的密码，请使用修改本人密码")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.user.password-admin-current-user"));
            assertThatThrownBy(() -> service.resetPassword("user-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("不能由管理员重置当前登录用户的密码，请使用修改本人密码")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.user.password-admin-current-user"));
        }

        verify(dao, never()).updateById(any(UserAccount.class));
    }

    @Test
    void shouldRejectCurrentUserForceLogout() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "Alice", "tenant-a"))) {
            assertThatThrownBy(() -> service.forceLogout("user-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("不能强制当前登录用户下线")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.user.force-logout-current-user"));
        }

        verify(dao, never()).query(any(Criteria.class), any(PageRequest.class));
    }

    @Test
    void shouldContributeCurrentUserPasswordAdministrationAvailability() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);

        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "Alice", "tenant-a"))) {
            assertThat(service.availability(UserAccountService.MODULE_ALIAS, "resetPassword", "user-1"))
                    .hasValueSatisfying(decision -> {
                        assertThat(decision.available()).isFalse();
                        assertThat(decision.reason()).isEqualTo("不能由管理员重置当前登录用户的密码，请使用修改本人密码");
                    });
            assertThat(service.availability(UserAccountService.MODULE_ALIAS, "changePassword", "user-1"))
                    .hasValueSatisfying(decision -> assertThat(decision.available()).isFalse());
            assertThat(service.availability(UserAccountService.MODULE_ALIAS, "forceLogout", "user-1"))
                    .hasValueSatisfying(decision -> {
                        assertThat(decision.available()).isFalse();
                        assertThat(decision.reason()).isEqualTo("不能强制当前登录用户下线");
                    });
            assertThat(service.availability(UserAccountService.MODULE_ALIAS, "resetPassword", "user-2"))
                    .isEmpty();
        }
    }

    @Test
    void shouldPreserveSecurityFieldsWhenUpdatingProfile() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount existing = activeUser();
        existing.setPasswordStatus(PasswordStatus.RESET_REQUIRED);
        existing.setPasswordChangedAt(java.time.Instant.parse("2026-07-01T00:00:00Z"));
        existing.setPasswordExpiresAt(java.time.Instant.parse("2026-07-02T00:00:00Z"));
        existing.setFailedLoginCount(3);
        existing.setLastLoginIp("127.0.0.1");
        UserAccountService service = userAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserAccount profile = activeUser();
        profile.setTitle("Alice Updated");
        profile.setPasswordStatus(PasswordStatus.NORMAL);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));

        service.beforeUpdate(profile);

        assertThat(profile.getPasswordStatus()).isEqualTo(PasswordStatus.RESET_REQUIRED);
        assertThat(profile.getPasswordExpiresAt()).isEqualTo(existing.getPasswordExpiresAt());
        assertThat(profile.getFailedLoginCount()).isEqualTo(3);
        assertThat(profile.getLastLoginIp()).isEqualTo("127.0.0.1");
    }

    private UserAccountService userAccountService(
            UserAccountDao dao,
            net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier activeTenantVerifier,
            PasswordHashingService passwordHashingService) {
        return new UserAccountService(dao, activeTenantVerifier, passwordHashingService);
    }

    private UserAccountServiceFixture userAccountServiceFixture(
            UserAccountDao dao,
            net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier activeTenantVerifier,
            PasswordHashingService passwordHashingService) {
        return new UserAccountServiceFixture(dao, activeTenantVerifier, passwordHashingService);
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId("user-1");
        user.setTenantId("tenant-a");
        user.setUsername("alice");
        user.setTitle("Alice");
        user.setEnabled(Boolean.TRUE);
        user.setPasswordHash(passwordHashingService.hash("secret1"));
        return user;
    }

    private AccountRoleGrant accountRoleGrant(String id, String roleId, String userId) {
        AccountRoleGrant grant = new AccountRoleGrant();
        grant.setId(id);
        grant.setTenantId("tenant-a");
        grant.setRoleId(roleId);
        grant.setUserId(userId);
        grant.setEnabled(Boolean.TRUE);
        return grant;
    }

    private QuerySchema.Field field(QuerySchema schema, String fieldName) {
        return schema.fields().stream()
                .filter(field -> fieldName.equals(field.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing query field: " + fieldName));
    }

    private static final class UserAccountServiceFixture {
        private final UserAccountDao dao;
        private final net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier activeTenantVerifier;
        private final PasswordHashingService passwordHashingService;
        private DataScopeCriteriaService dataScopeCriteriaService =
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService();
        private PasswordPolicyRuleService passwordPolicyRuleService;
        private AccountRoleGrantDao accountRoleGrantDao = mock(AccountRoleGrantDao.class);
        private UserSecurityEventPublisher securityEventPublisher = UserSecurityEventPublisher.NOOP;
        private UserSessionRevocationService sessionRevocationService = mock(UserSessionRevocationService.class);
        private UserSessionPresenceService sessionPresenceService = mock(UserSessionPresenceService.class);

        private UserAccountServiceFixture(
                UserAccountDao dao,
                net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier activeTenantVerifier,
                PasswordHashingService passwordHashingService) {
            this.dao = dao;
            this.activeTenantVerifier = activeTenantVerifier;
            this.passwordHashingService = passwordHashingService;
        }

        UserAccountServiceFixture dataScope(DataScopeCriteriaService value) {
            dataScopeCriteriaService = value;
            return this;
        }

        UserAccountServiceFixture passwordPolicy(PasswordPolicyRuleService value) {
            passwordPolicyRuleService = value;
            return this;
        }

        UserAccountServiceFixture roleGrants(AccountRoleGrantDao value) {
            accountRoleGrantDao = value;
            return this;
        }

        UserAccountServiceFixture securityEvents(UserSecurityEventPublisher value) {
            securityEventPublisher = value;
            return this;
        }

        UserAccountServiceFixture sessionRevocation(UserSessionRevocationService value) {
            sessionRevocationService = value;
            return this;
        }

        UserAccountServiceFixture sessionPresence(UserSessionPresenceService value) {
            sessionPresenceService = value;
            return this;
        }

        UserAccountService build() {
            return new UserAccountService(
                    dao,
                    activeTenantVerifier,
                    passwordHashingService,
                    new UserAccountAuthorizationServices(() -> dataScopeCriteriaService, accountRoleGrantDao),
                    new UserAccountSecurityServices(Optional.ofNullable(passwordPolicyRuleService),
                            securityEventPublisher, sessionRevocationService, sessionPresenceService));
        }
    }

    private static final class RecordingUserSecurityEventPublisher implements UserSecurityEventPublisher {
        private final List<UserSecurityEvent> events = new ArrayList<>();

        @Override
        public void publish(UserSecurityEvent event) {
            events.add(event);
        }
    }
}
