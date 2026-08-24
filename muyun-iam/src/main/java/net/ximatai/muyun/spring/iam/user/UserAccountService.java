package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjectionContributor;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityContributor;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.initialdata.PlatformInitialAdminSettings;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataOptions;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class UserAccountService extends TenantActiveScopedService<UserAccount> implements
        EnableAbility<UserAccount>,
        ReferenceAbility<UserAccount>,
        ModuleReadProjectionContributor,
        DataScopeAbility<UserAccount>,
        InitialDataAbility<UserAccount>,
        QueryAbility<UserAccount>,
        RecordActionAvailabilityContributor {
    public static final String MODULE_ALIAS = "iam.user";
    public static final String PLATFORM_SUPER_ADMIN_USER_ID = "platform.user.super_admin";
    public static final String PLATFORM_SUPER_ADMIN_USERNAME = "admin";
    private static final int TEMPORARY_PASSWORD_MAX_ATTEMPTS = 32;

    private final PasswordHashingService passwordHashingService;
    private final PasswordPolicyRuleService passwordPolicyRuleService;
    private final AccountRoleGrantDao accountRoleGrantDao;
    private final Supplier<DataScopeCriteriaService> dataScopeCriteriaService;
    private final UserSecurityEventPublisher userSecurityEventPublisher;
    private final UserSessionRevocationService userSessionRevocationService;
    private final UserSessionPresenceService userSessionPresenceService;
    private final SecureRandom secureRandom = new SecureRandom();
    private PlatformInitialAdminSettings initialAdminSettings = PlatformInitialAdminSettings.defaults();
    private static final ActionExecutionPolicy CHANGE_PASSWORD_POLICY = new ActionExecutionPolicy(
            "changePassword",
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );

    UserAccountService(UserAccountDao userAccountDao,
                       ActiveTenantVerifier activeTenantVerifier,
                       PasswordHashingService passwordHashingService) {
        this(userAccountDao, activeTenantVerifier, passwordHashingService,
                null, null, AllowAllDataScopeCriteriaService::new,
                UserSecurityEventPublisher.NOOP, null, null);
    }

    @Autowired
    public UserAccountService(UserAccountDao userAccountDao,
                              ActiveTenantVerifier activeTenantVerifier,
                              PasswordHashingService passwordHashingService,
                              UserAccountAuthorizationServices authorizationServices,
                              UserAccountSecurityServices securityServices) {
        this(userAccountDao, activeTenantVerifier, passwordHashingService,
                securityServices.passwordPolicyRuleService().orElse(null),
                authorizationServices.accountRoleGrantDao(),
                authorizationServices.dataScopeCriteriaService(),
                securityServices.securityEventPublisher(),
                securityServices.sessionRevocationService(),
                securityServices.sessionPresenceService());
    }

    private UserAccountService(UserAccountDao userAccountDao,
                               ActiveTenantVerifier activeTenantVerifier,
                               PasswordHashingService passwordHashingService,
                               PasswordPolicyRuleService passwordPolicyRuleService,
                               AccountRoleGrantDao accountRoleGrantDao,
                               Supplier<DataScopeCriteriaService> dataScopeCriteriaService,
                               UserSecurityEventPublisher userSecurityEventPublisher,
                               UserSessionRevocationService userSessionRevocationService,
                               UserSessionPresenceService userSessionPresenceService) {
        super(MODULE_ALIAS, UserAccount.class, userAccountDao, activeTenantVerifier);
        this.passwordHashingService = passwordHashingService;
        this.passwordPolicyRuleService = passwordPolicyRuleService;
        this.accountRoleGrantDao = accountRoleGrantDao;
        this.dataScopeCriteriaService = dataScopeCriteriaService;
        this.userSecurityEventPublisher = userSecurityEventPublisher;
        this.userSessionRevocationService = userSessionRevocationService;
        this.userSessionPresenceService = userSessionPresenceService;
    }

    @Autowired
    public void setInitialAdminSettings(Optional<PlatformInitialAdminSettings> settings) {
        this.initialAdminSettings = settings.orElseGet(PlatformInitialAdminSettings::defaults);
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("platform.system-admin-user", 50);
    }

    @Override
    public List<UserAccount> initialData() {
        UserAccount user = new UserAccount();
        user.setId(PLATFORM_SUPER_ADMIN_USER_ID);
        user.setUsername(PLATFORM_SUPER_ADMIN_USERNAME);
        user.setPassword(initialAdminSettings.initialPassword());
        user.setAuthUserId(user.getId());
        user.setAuthModuleAlias(MODULE_ALIAS);
        user.setEnabled(Boolean.TRUE);
        return List.of(user);
    }

    @Override
    public DataScopeCriteriaService getDataScopeCriteriaService() {
        return dataScopeCriteriaService.get();
    }

    @Override
    public Optional<RecordActionAvailabilityDecision> availability(String moduleAlias,
                                                                  String actionCode,
                                                                  String recordId) {
        if (!MODULE_ALIAS.equals(moduleAlias)
                || (!"changePassword".equals(actionCode)
                && !"resetPassword".equals(actionCode)
                && !"forceLogout".equals(actionCode))) {
            return Optional.empty();
        }
        return CurrentUserContext.currentUser()
                .filter(currentUser -> currentUser.userId().equals(recordId))
                .map(currentUser -> RecordActionAvailabilityDecision.unavailable(selfAdministrationReason(actionCode)));
    }

    @Override
    public Map<String, Optional<RecordActionAvailabilityDecision>> availability(String moduleAlias,
                                                                                  String actionCode,
                                                                                  Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Optional<RecordActionAvailabilityDecision>> result = new HashMap<>();
        for (String recordId : recordIds) {
            result.put(recordId, availability(moduleAlias, actionCode, recordId));
        }
        return Map.copyOf(result);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptor.builder(MODULE_ALIAS)
                .field(QueryField.of("id", QueryOperator.EQ, QueryOperator.IN).withTitle("ID"))
                .field(QueryField.of("tenantId", QueryOperator.EQ, QueryOperator.IN, QueryOperator.NULL).withTitle("租户"))
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("启用状态"))
                .field(QueryField.of("username", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("账号").withQuickSearch().withSortable())
                .field(QueryField.of("passwordStatus", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("密码状态"))
                .field(QueryField.of("lastLoginAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("最后登录时间")
                        .withSortable())
                .field(QueryField.of("createdAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("创建时间")
                        .withSortable())
                .field(QueryField.of("updatedAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("更新时间")
                        .withSortable())
                // The tenant navigator owns this scope; it is not an arbitrary client-side filter.
                .externalCriteria("tenantId", QueryValueType.STRING, ExternalQueryValueSource.PAGE_CONTEXT, value -> Criteria.of()
                        .eq("tenantId", Preconditions.requireText(String.valueOf(value), "tenantId")))
                .externalCriteria("onlineOnly", QueryValueType.BOOLEAN, ExternalQueryValueSource.USER_INPUT,
                        this::onlineUserCriteria)
                .defaultSort(net.ximatai.muyun.database.core.orm.Sort.asc("username"))
                .build();
    }

    private Criteria onlineUserCriteria(Object value) {
        if (!Boolean.TRUE.equals(value)) {
            return Criteria.of();
        }
        if (userSessionPresenceService == null) {
            return Criteria.of().in("id", List.of("__no_active_user__"));
        }
        return userSessionPresenceService.activeAccountCriteria();
    }

    @Override
    public List<ModuleReadProjection> moduleReadProjections() {
        return List.of(
                ModuleReadProjection.filterable(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getEmployeeNo),
                        "employeeNo"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getTitle),
                        "employeeTitle"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .select(EmployeeAccount::getEmployeeId),
                        "employeeId"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getOrganizationId),
                        "employeeOrganizationId"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .then(Employee::getOrganizationId)
                                .select(Organization::getTitle),
                        "organizationTitle"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getDepartmentId),
                        "employeeDepartmentId"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .then(Employee::getDepartmentId)
                                .select(Department::getTitle),
                        "departmentTitle")
        );
    }

    @Override
    public void normalizeBeforeMutation(UserAccount user) {
        String username = requireUsername(user.getUsername());
        user.setUsername(username);
        user.setTitle(username);
        user.setSortOrder(null);
        user.setAuthOrganizationId(null);
        user.setAuthModuleAlias(MODULE_ALIAS);
    }

    @Override
    public void beforePrepareInsert(UserAccount user) {
        if (!TenantContext.isSystem() || user.getTenantId() != null) {
            requireActiveTenantMutationContext();
        }
        normalizeBeforeMutation(user);
    }

    @Override
    public void beforeInsert(UserAccount user) {
        syncSelfAuthUser(user);
        validatePasswordPolicy(user.getPassword());
        user.setPasswordHash(passwordHashingService.hash(user.getPassword()));
        user.setPasswordStatus(user.getPasswordStatus() == null ? PasswordStatus.INITIAL : user.getPasswordStatus());
        user.setPasswordChangedAt(user.getPasswordChangedAt() == null ? Instant.now() : user.getPasswordChangedAt());
        user.setFailedLoginCount(user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount());
        rejectDuplicateUsername(user);
    }

    @Override
    public void beforeUpdate(UserAccount user) {
        UserAccount existing = select(user.getId());
        if (existing != null) {
            preserveSecurityFields(user, existing);
        }
        syncSelfAuthUser(user);
        rejectDuplicateUsername(user);
    }

    @Override
    public void afterDelete(String id, UserAccount entity, int deleted) {
        cleanupDeletedUserReferences(id);
    }

    public void cleanupDeletedUserReferences(String userId) {
        if (accountRoleGrantDao == null) {
            return;
        }
        String validUserId = Preconditions.requireText(userId, "userId");
        accountRoleGrantDao.query(activeCriteria(Criteria.of().eq("userId", validUserId)),
                        new PageRequest(0, Integer.MAX_VALUE))
                .forEach(grant -> accountRoleGrantDao.deleteById(grant.getId()));
    }

    public AccountRoleGrantUserIdRepairResult repairAccountRoleGrantUserIds() {
        if (accountRoleGrantDao == null) {
            return AccountRoleGrantUserIdRepairResult.empty();
        }
        List<UserAccount> users = getDao().query(activeCriteria(Criteria.of()), new PageRequest(0, Integer.MAX_VALUE));
        List<AccountRoleGrant> grants = accountRoleGrantDao.query(activeCriteria(Criteria.of()),
                new PageRequest(0, Integer.MAX_VALUE));
        Map<String, UserAccount> usersByTenantAndId = new HashMap<>();
        Map<String, UserAccount> usersByTenantAndUsername = new HashMap<>();
        for (UserAccount user : users) {
            usersByTenantAndId.put(tenantKey(user.getTenantId(), user.getId()), user);
            usersByTenantAndUsername.put(tenantKey(user.getTenantId(), user.getUsername()), user);
        }

        int updated = 0;
        int deletedDuplicates = 0;
        int skipped = 0;
        for (AccountRoleGrant grant : grants) {
            String grantUserId = grant.getUserId();
            if (grantUserId == null || usersByTenantAndId.containsKey(tenantKey(grant.getTenantId(), grantUserId))) {
                continue;
            }
            UserAccount user = usersByTenantAndUsername.get(tenantKey(grant.getTenantId(), grantUserId));
            if (user == null) {
                skipped++;
                continue;
            }
            AccountRoleGrant duplicate = findDuplicateAccountRoleGrant(grants, grant, user.getId());
            if (duplicate != null) {
                if (Boolean.TRUE.equals(grant.getEnabled()) && !Boolean.TRUE.equals(duplicate.getEnabled())) {
                    duplicate.setEnabled(Boolean.TRUE);
                    EntityLifecycle.prepareUpdate(duplicate, Instant.now());
                    accountRoleGrantDao.updateById(duplicate);
                    updated++;
                }
                accountRoleGrantDao.deleteById(grant.getId());
                deletedDuplicates++;
                continue;
            }
            grant.setUserId(user.getId());
            EntityLifecycle.prepareUpdate(grant, Instant.now());
            accountRoleGrantDao.updateById(grant);
            updated++;
        }
        return new AccountRoleGrantUserIdRepairResult(updated, deletedDuplicates, skipped);
    }

    public String createUser(UserAccount user, String password) {
        user.setPassword(password);
        return insert(user);
    }

    public UserAccount selectForView(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        if (TenantContext.isSystem()) {
            UserAccount systemUser = selectSystemUser(validUserId);
            if (systemUser != null) {
                return systemUser;
            }
        }
        return DataScopeAbility.super.selectForAction(PlatformAction.VIEW, validUserId);
    }

    public int changePassword(String userId, String newPassword) {
        String validUserId = Preconditions.requireText(userId, "userId");
        rejectCurrentUserPasswordAdministration(validUserId);
        requireRecordScope(currentRecordMutationPolicy(), List.of(validUserId));
        UserAccount user = requireEnabled(validUserId,
                "user is not active: " + userId);
        validatePasswordPolicy(newPassword);
        user.setPasswordHash(passwordHashingService.hash(newPassword));
        user.setPasswordStatus(PasswordStatus.NORMAL);
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordExpiresAt(null);
        int count = getDao().updateById(user);
        if (count > 0) {
            revokeUserSessions(validUserId, "password changed");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordChanged(validUserId));
        }
        return count;
    }

    public PasswordResetResult resetPassword(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        rejectCurrentUserPasswordAdministration(validUserId);
        requireRecordScope(resetPasswordPolicy(), List.of(validUserId));
        UserAccount user = requireEnabled(validUserId,
                "user is not active: " + userId);
        String temporaryPassword = generateTemporaryPassword();
        Instant now = Instant.now();
        user.setPasswordHash(passwordHashingService.hash(temporaryPassword));
        user.setPasswordStatus(PasswordStatus.RESET_REQUIRED);
        user.setPasswordChangedAt(now);
        user.setPasswordExpiresAt(now.plusSeconds(86_400));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        int count = getDao().updateById(user);
        if (count > 0) {
            revokeUserSessions(validUserId, "password reset");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordReset(validUserId));
        }
        return new PasswordResetResult(count, count > 0 ? temporaryPassword : null, user.getPasswordExpiresAt());
    }

    public int forceLogout(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        rejectCurrentUserForceLogout(validUserId);
        requireRecordScope(forceLogoutPolicy(), List.of(validUserId));
        UserAccount user = select(validUserId);
        if (user == null) {
            return 0;
        }
        int revoked = revokeUserSessions(validUserId, "force logout");
        userSecurityEventPublisher.publish(UserSecurityEvent.forceLogout(validUserId));
        return revoked;
    }

    public int changeOwnPassword(String userId, String currentPassword, String newPassword) {
        String validUserId = Preconditions.requireText(userId, "userId");
        UserAccount user = requireEnabled(validUserId,
                "user is not active: " + userId);
        if (!passwordMatches(user, currentPassword)) {
            throw new AuthenticationFailedException("invalid username or password");
        }
        validatePasswordPolicy(newPassword);
        user.setPasswordHash(passwordHashingService.hash(newPassword));
        user.setPasswordStatus(PasswordStatus.NORMAL);
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordExpiresAt(null);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        int count = getDao().updateById(user);
        if (count > 0) {
            revokeUserSessions(validUserId, "own password changed");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordChanged(validUserId));
        }
        return count;
    }

    private int revokeUserSessions(String userId, String reason) {
        return userSessionRevocationService == null
                ? 0
                : userSessionRevocationService.revokeUserSessions(userId, reason);
    }

    public boolean passwordChangeRequired(UserAccount user, Instant now) {
        if (user == null) {
            return false;
        }
        if (passwordExpired(user, now)) {
            return true;
        }
        PasswordStatus status = effectivePasswordStatus(user);
        return status == PasswordStatus.INITIAL
                || status == PasswordStatus.RESET_REQUIRED
                || status == PasswordStatus.EXPIRED;
    }

    public boolean resetPasswordExpired(UserAccount user, Instant now) {
        return effectivePasswordStatus(user) == PasswordStatus.RESET_REQUIRED
                && passwordExpired(user, now);
    }

    public PasswordStatus effectivePasswordStatus(UserAccount user) {
        return user == null || user.getPasswordStatus() == null ? PasswordStatus.NORMAL : user.getPasswordStatus();
    }

    public void recordLoginSuccess(String userId, Instant loginAt, String ip, String userAgent) {
        UserAccount user = select(userId);
        if (user == null) {
            return;
        }
        user.setLastLoginAt(loginAt);
        user.setLastLoginIp(normalizeLength(ip, 64));
        user.setLastLoginUserAgent(normalizeLength(userAgent, 512));
        user.setFailedLoginCount(0);
        updateLoginAudit(user);
    }

    public void recordLoginFailure(UserAccount user, Instant failedAt) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            return;
        }
        UserAccount latest = select(user.getId());
        if (latest == null) {
            return;
        }
        latest.setLastFailedLoginAt(failedAt);
        latest.setFailedLoginCount((latest.getFailedLoginCount() == null ? 0 : latest.getFailedLoginCount()) + 1);
        updateLoginAudit(latest);
    }

    public UserAccount requireActiveUser(String username) {
        return requireActiveUser(TenantContext.currentTenantId().orElse(null), username);
    }

    public UserAccount requireActiveUser(String tenantId, String username) {
        String validUsername = requireUsername(username);
        UserAccount user = findOne(Criteria.of()
                .eq("username", validUsername)
                .eqNullable("tenantId", normalizeBlank(tenantId)));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthenticationFailedException("invalid username or password");
        }
        return user;
    }

    public boolean passwordMatches(UserAccount user, String password) {
        return user != null && passwordHashingService.matches(password, user.getPasswordHash());
    }

    private void rejectDuplicateUsername(UserAccount user) {
        rejectDuplicate(user, Criteria.of()
                        .eq("username", user.getUsername())
                        .eqNullable("tenantId", user.getTenantId()),
                "username must be unique within tenant: " + user.getUsername());
    }

    private UserAccount selectSystemUser(String userId) {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("system user account view")) {
            UserAccount user = select(userId);
            return user != null && normalizeBlank(user.getTenantId()) == null ? user : null;
        }
    }

    private void rejectCurrentUserPasswordAdministration(String userId) {
        CurrentUserContext.currentUser()
                .filter(currentUser -> currentUser.userId().equals(userId))
                .ifPresent(currentUser -> {
                    throw BusinessExceptions.warning(
                            "iam.user.password-admin-current-user",
                            "不能由管理员重置当前登录用户的密码，请使用修改本人密码");
                });
    }

    private void rejectCurrentUserForceLogout(String userId) {
        CurrentUserContext.currentUser()
                .filter(currentUser -> currentUser.userId().equals(userId))
                .ifPresent(currentUser -> {
                    throw BusinessExceptions.warning(
                            "iam.user.force-logout-current-user",
                            "不能强制当前登录用户下线");
                });
    }

    private String selfAdministrationReason(String actionCode) {
        if ("forceLogout".equals(actionCode)) {
            return "不能强制当前登录用户下线";
        }
        return "不能由管理员重置当前登录用户的密码，请使用修改本人密码";
    }

    private String requireUsername(String username) {
        return Preconditions.requireText(username, "username").trim();
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void syncSelfAuthUser(UserAccount user) {
        if (user.getId() != null && !user.getId().isBlank()) {
            user.setAuthUserId(user.getId());
        }
    }

    private void preserveSecurityFields(UserAccount user, UserAccount existing) {
        user.setPasswordHash(existing.getPasswordHash());
        user.setPasswordStatus(existing.getPasswordStatus());
        user.setPasswordChangedAt(existing.getPasswordChangedAt());
        user.setPasswordExpiresAt(existing.getPasswordExpiresAt());
        user.setLastLoginAt(existing.getLastLoginAt());
        user.setLastLoginIp(existing.getLastLoginIp());
        user.setLastLoginUserAgent(existing.getLastLoginUserAgent());
        user.setLastFailedLoginAt(existing.getLastFailedLoginAt());
        user.setFailedLoginCount(existing.getFailedLoginCount());
        user.setLockedUntil(existing.getLockedUntil());
    }

    private void validatePasswordPolicy(String password) {
        if (passwordPolicyRuleService != null) {
            passwordPolicyRuleService.validatePassword(password);
            return;
        }
        if (password == null || password.length() < 6) {
            throw BusinessExceptions.warning("iam.user.password-policy-violated", "密码长度不能少于 6 位");
        }
    }

    private ActionExecutionPolicy currentRecordMutationPolicy() {
        return ActionExecutionContextHolder.current()
                .filter(context -> MODULE_ALIAS.equals(context.moduleAlias()))
                .map(context -> context.actionPolicy())
                .orElse(CHANGE_PASSWORD_POLICY);
    }

    private ActionExecutionPolicy resetPasswordPolicy() {
        return ActionExecutionContextHolder.current()
                .filter(context -> MODULE_ALIAS.equals(context.moduleAlias()))
                .map(context -> context.actionPolicy())
                .orElse(new ActionExecutionPolicy(
                        "resetPassword",
                        PlatformActionLevel.RECORD,
                        ActionAccessMode.AUTH_REQUIRED,
                        true,
                        true,
                        ActionDefaultGrantPolicy.NONE,
                        null
                ));
    }

    private ActionExecutionPolicy forceLogoutPolicy() {
        return ActionExecutionContextHolder.current()
                .filter(context -> MODULE_ALIAS.equals(context.moduleAlias()))
                .map(context -> context.actionPolicy())
                .orElse(new ActionExecutionPolicy(
                        "forceLogout",
                        PlatformActionLevel.RECORD,
                        ActionAccessMode.AUTH_REQUIRED,
                        true,
                        true,
                        ActionDefaultGrantPolicy.NONE,
                        null
                ));
    }

    private String generateTemporaryPassword() {
        for (int attempt = 0; attempt < TEMPORARY_PASSWORD_MAX_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[12];
            secureRandom.nextBytes(bytes);
            String temporaryPassword = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                validatePasswordPolicy(temporaryPassword);
                return temporaryPassword;
            } catch (PlatformException ignored) {
                // Configured regex rules can reject random candidates; try another bounded candidate.
            }
        }
        throw BusinessExceptions.warning("iam.user.temporary-password-unavailable",
                "无法生成符合当前密码策略的临时密码");
    }

    private boolean passwordExpired(UserAccount user, Instant now) {
        return user != null
                && user.getPasswordExpiresAt() != null
                && now != null
                && !now.isBefore(user.getPasswordExpiresAt());
    }

    private void updateLoginAudit(UserAccount user) {
        if (user.getVersion() == null) {
            getDao().updateById(user);
            return;
        }
        getDao().updateByIdAndVersion(user, user.getVersion());
    }

    private String normalizeLength(String value, int maxLength) {
        String normalized = normalizeBlank(value);
        if (normalized == null || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private AccountRoleGrant findDuplicateAccountRoleGrant(List<AccountRoleGrant> grants,
                                                           AccountRoleGrant source,
                                                           String normalizedUserId) {
        return grants.stream()
                .filter(candidate -> !Objects.equals(candidate.getId(), source.getId()))
                .filter(candidate -> Objects.equals(candidate.getRoleId(), source.getRoleId()))
                .filter(candidate -> Objects.equals(candidate.getUserId(), normalizedUserId))
                .filter(candidate -> candidate.getManagementScopeType() == source.getManagementScopeType())
                .filter(candidate -> Objects.equals(candidate.getManagementScopeId(), source.getManagementScopeId()))
                .findFirst()
                .orElse(null);
    }

    private String tenantKey(String tenantId, String value) {
        return String.valueOf(tenantId) + "\u0000" + value;
    }

    public record PasswordResetResult(int count, String temporaryPassword, Instant expiresAt) {
    }

    public record AccountRoleGrantUserIdRepairResult(int updated, int deletedDuplicates, int skipped) {
        static AccountRoleGrantUserIdRepairResult empty() {
            return new AccountRoleGrantUserIdRepairResult(0, 0, 0);
        }
    }
}
