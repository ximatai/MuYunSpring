package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
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
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
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
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
    private final UserSecurityEventPublisher userSecurityEventPublisher;
    private final UserSessionRevocationService userSessionRevocationService;
    private final UserSessionPresenceService userSessionPresenceService;
    private final PersonalAccessTokenStore personalAccessTokens;
    private final SecureRandom secureRandom = new SecureRandom();
    private PlatformInitialAdminSettings initialAdminSettings = PlatformInitialAdminSettings.defaults();
    private LoginLockoutPolicy loginLockoutPolicy = LoginLockoutPolicy.defaults();
    private static final ActionExecutionPolicy CHANGE_PASSWORD_POLICY = new ActionExecutionPolicy(
            "changePassword",
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );

    @Autowired
    public UserAccountService(UserAccountDao userAccountDao,
                              ActiveTenantVerifier activeTenantVerifier,
                              PasswordHashingService passwordHashingService,
                              AccountRoleGrantDao accountRoleGrantDao,
                              UserAccountSecurityServices securityServices) {
        super(MODULE_ALIAS, UserAccount.class, userAccountDao, activeTenantVerifier);
        this.passwordHashingService = Objects.requireNonNull(passwordHashingService, "passwordHashingService");
        this.passwordPolicyRuleService = securityServices.passwordPolicyRuleService().orElse(null);
        this.accountRoleGrantDao = Objects.requireNonNull(accountRoleGrantDao, "accountRoleGrantDao");
        this.userSecurityEventPublisher = securityServices.securityEventPublisher();
        this.userSessionRevocationService = securityServices.sessionRevocationService();
        this.userSessionPresenceService = securityServices.sessionPresenceService();
        this.personalAccessTokens = securityServices.personalAccessTokenStore();
    }

    @Autowired
    public void setInitialAdminSettings(Optional<PlatformInitialAdminSettings> settings) {
        this.initialAdminSettings = settings.orElseGet(PlatformInitialAdminSettings::defaults);
    }

    @Autowired
    public void setLoginLockoutPolicy(Optional<LoginLockoutPolicy> policy) {
        this.loginLockoutPolicy = policy.orElseGet(LoginLockoutPolicy::defaults);
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
                .map(currentUser -> RecordActionAvailabilityDecision.unavailableText(selfAdministrationReason(actionCode)));
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
        user.setTitle(user.getUsername());
        user.setSortOrder(null);
        user.setAuthOrganizationId(null);
        user.setAuthModuleAlias(MODULE_ALIAS);
    }

    @Override
    public void requireMutationContext(UserAccount user) {
        if (TenantContext.isSystem() && (user == null || user.getTenantId() == null)) {
            return;
        }
        requireActiveTenantMutationContext();
    }

    @Override
    public void beforeInsert(UserAccount user) {
        syncSelfAuthUser(user);
        if (user.getPasswordStatus() == PasswordStatus.NONE) {
            if (user.getPassword() != null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "iam.rejection.user-account.passwordless-account-must-not-carry-password",
                        "account without local password must not carry a password"), Map.of());
            }
            user.setPasswordHash(null);
            user.setPasswordChangedAt(null);
            user.setPasswordExpiresAt(null);
        } else {
            validatePasswordPolicy(user.getPassword());
            user.setPasswordHash(passwordHashingService.hash(user.getPassword()));
            user.setPasswordStatus(user.getPasswordStatus() == null ? PasswordStatus.INITIAL : user.getPasswordStatus());
            user.setPasswordChangedAt(user.getPasswordChangedAt() == null ? Instant.now() : user.getPasswordChangedAt());
        }
        user.setFailedLoginCount(user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount());
        rejectDuplicateUsername(user);
    }

    @Override
    public void beforeUpdate(UserAccount user, UserAccount existing) {
        if (existing != null) {
            preserveSecurityFields(user, existing);
        }
        syncSelfAuthUser(user);
        rejectDuplicateUsername(user);
    }

    @Override
    public void afterUpdate(UserAccount user, int updated) {
        if (updated > 0 && Boolean.FALSE.equals(user.getEnabled())) {
            revokeUserSessions(user.getId(), "user disabled");
            personalAccessTokens.revokeForUser(user.getId(), "user disabled");
        }
    }

    @Override
    public void afterDelete(String id, UserAccount entity, int deleted) {
        if (deleted > 0) personalAccessTokens.revokeForUser(id, "user deleted");
        cleanupDeletedUserReferences(id);
    }

    public void cleanupDeletedUserReferences(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        accountRoleGrantDao.query(activeCriteria(Criteria.of().eq("userId", validUserId)),
                        new PageRequest(0, Integer.MAX_VALUE))
                .forEach(grant -> accountRoleGrantDao.deleteById(grant.getId()));
    }

    public AccountRoleGrantUserIdRepairResult repairAccountRoleGrantUserIds() {
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

    @Transactional
    public int changePassword(String userId, String newPassword) {
        String validUserId = Preconditions.requireText(userId, "userId");
        rejectCurrentUserPasswordAdministration(validUserId);
        int count = mutateFields(currentRecordMutationPolicy(), validUserId, user -> {
            requireCommandUserEnabled(user);
            validatePasswordPolicy(newPassword);
            user.setPasswordHash(passwordHashingService.hash(newPassword));
            user.setPasswordStatus(PasswordStatus.NORMAL);
            user.setPasswordChangedAt(Instant.now());
            user.setPasswordExpiresAt(null);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }, "passwordHash", "passwordStatus", "passwordChangedAt", "passwordExpiresAt", "failedLoginCount", "lockedUntil");
        if (count > 0) {
            revokeUserSessions(validUserId, "password changed");
            personalAccessTokens.revokeForUser(validUserId, "administrator changed password");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordChanged(validUserId));
        }
        return count;
    }

    @Transactional
    public PasswordResetResult resetPassword(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        rejectCurrentUserPasswordAdministration(validUserId);
        String temporaryPassword = generateTemporaryPassword();
        Instant expiresAt = Instant.now().plusSeconds(86_400);
        int count = mutateFields(resetPasswordPolicy(), validUserId, user -> {
            requireCommandUserEnabled(user);
            user.setPasswordHash(passwordHashingService.hash(temporaryPassword));
            user.setPasswordStatus(PasswordStatus.RESET_REQUIRED);
            user.setPasswordChangedAt(Instant.now());
            user.setPasswordExpiresAt(expiresAt);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }, "passwordHash", "passwordStatus", "passwordChangedAt", "passwordExpiresAt", "failedLoginCount", "lockedUntil");
        if (count > 0) {
            revokeUserSessions(validUserId, "password reset");
            personalAccessTokens.revokeForUser(validUserId, "password reset");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordReset(validUserId));
        }
        return new PasswordResetResult(count, count > 0 ? temporaryPassword : null, count > 0 ? expiresAt : null);
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

    /**
     * Verifying the current password is a credential attempt: it obeys the login lock and counts failures,
     * which must commit even though the password change itself is rejected. The check runs before the
     * field mutation opens its transaction scope, because a rejection thrown from inside that scope would
     * mark the surrounding transaction rollback-only and discard the counted failure.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public int changeOwnPassword(String userId, String currentPassword, String newPassword) {
        String validUserId = Preconditions.requireText(userId, "userId");
        Instant attemptedAt = Instant.now();
        requireRecordScope(ownPasswordPolicy(), List.of(validUserId));
        UserAccount verified = selectActiveRaw(validUserId);
        if (verified == null) {
            return 0;
        }
        requireCommandUserEnabled(verified);
        if (loginLocked(verified, attemptedAt)) {
            throw new AuthenticationFailedException("invalid username or password");
        }
        if (!passwordMatches(verified, currentPassword)) {
            recordLoginFailure(verified, attemptedAt);
            throw new AuthenticationFailedException("invalid username or password");
        }
        int count = mutateFields(ownPasswordPolicy(), validUserId, user -> {
            requireCommandUserEnabled(user);
            // The verified password must still be the stored one; a concurrent change or lock makes it stale.
            if (loginLocked(user, attemptedAt) || !Objects.equals(user.getPasswordHash(), verified.getPasswordHash())) {
                throw new AuthenticationFailedException("invalid username or password");
            }
            applyOwnPassword(user, newPassword);
        }, "passwordHash", "passwordStatus", "passwordChangedAt", "passwordExpiresAt", "failedLoginCount", "lockedUntil");
        if (count > 0) {
            revokeUserSessions(validUserId, "own password changed");
            userSecurityEventPublisher.publish(UserSecurityEvent.passwordChanged(validUserId));
        }
        return count;
    }

    private void applyOwnPassword(UserAccount user, String newPassword) {
        validatePasswordPolicy(newPassword);
        user.setPasswordHash(passwordHashingService.hash(newPassword));
        user.setPasswordStatus(PasswordStatus.NORMAL);
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordExpiresAt(null);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
    }

    private void requireCommandUserEnabled(UserAccount user) {
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.user-account.user-is-not-active",
                    "user is not active: {value1}"), Map.of("value1", String.valueOf(user.getId())));
        }
    }

    private int revokeUserSessions(String userId, String reason) {
        return userSessionRevocationService.revokeUserSessions(userId, reason);
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

    /**
     * Admits a verified credential against the latest stored account and opens the session in the same
     * transaction. Admission requires the account to still be enabled; a password credential additionally
     * requires the account to be unlocked and to still store the exact password that was verified, and its
     * success clears the failure count. The password lock belongs to the local password, so other methods
     * neither check nor clear it. The login-state update keeps the account row write-locked until the session
     * is persisted, so a concurrent password change, reset or disablement either makes this admission fail or
     * waits for it and then revokes the new session.
     *
     * @param openSession receives the admitted latest account; it must only persist the session
     * @return the session result, or empty when the account no longer admits this login
     * @hidden Internal IAM collaborator of the session entry.
     */
    public <R> Optional<R> admitLogin(UserAccount verified, boolean passwordCredential, Instant loginAt,
                                      String ip, String userAgent, Function<UserAccount, R> openSession) {
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(openSession, "openSession");
        return inMutationTransaction(() -> {
            UserAccount admitted = VersionedRecordMutation.update(getDao(),
                    () -> admissible(selectActiveRaw(verified.getId()), verified, passwordCredential, loginAt), user -> {
                        user.setLastLoginAt(loginAt);
                        user.setLastLoginIp(normalizeLength(ip, 64));
                        user.setLastLoginUserAgent(normalizeLength(userAgent, 512));
                        if (passwordCredential) {
                            user.setFailedLoginCount(0);
                            user.setLockedUntil(null);
                        }
                    });
            return admitted == null ? Optional.<R>empty() : Optional.of(openSession.apply(admitted));
        });
    }

    /** Internal credential issuance admission; holds the account row lock without changing login facts. */
    <R> R withPersonalAccessTokenOwner(CurrentUser expectedOwner, Function<UserAccount, R> issue) {
        return inMutationTransaction(() -> {
            UserAccount owner = VersionedRecordMutation.update(getDao(), () -> {
                UserAccount latest = selectActiveRaw(expectedOwner.userId());
                return latest != null && Boolean.TRUE.equals(latest.getEnabled())
                        && Objects.equals(latest.getTenantId(), expectedOwner.tenantId()) ? latest : null;
            }, user -> {});
            if (owner == null) throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                    "personal access token owner is unavailable");
            return issue.apply(owner);
        });
    }

    private UserAccount admissible(UserAccount latest, UserAccount verified, boolean passwordCredential, Instant at) {
        if (!passwordCredential) {
            return latest != null && Boolean.TRUE.equals(latest.getEnabled()) ? latest : null;
        }
        UserAccount eligible = loginEligible(latest, at);
        return eligible != null && Objects.equals(eligible.getPasswordHash(), verified.getPasswordHash())
                ? eligible : null;
    }

    /**
     * Counts a failed credential attempt. Reaching the lockout threshold locks the account and restarts
     * the count, so an expired lock grants a fresh attempt budget instead of relocking on the next miss.
     */
    public void recordLoginFailure(UserAccount user, Instant failedAt) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            return;
        }
        LoginLockoutPolicy policy = loginLockoutPolicy;
        // Failures that race past an already applied lock neither extend it nor restart the attempt budget.
        VersionedRecordMutation.update(getDao(), () -> unlocked(selectActiveRaw(user.getId()), failedAt), latest -> {
            latest.setLastFailedLoginAt(failedAt);
            int failures = (latest.getFailedLoginCount() == null ? 0 : latest.getFailedLoginCount()) + 1;
            if (policy.enabled() && failures >= policy.maxFailedAttempts()) {
                latest.setLockedUntil(failedAt.plus(policy.lockDuration()));
                latest.setFailedLoginCount(0);
            } else {
                latest.setFailedLoginCount(failures);
            }
        });
    }

    boolean loginLocked(UserAccount user, Instant now) {
        return user != null && user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
    }

    private UserAccount loginEligible(UserAccount user, Instant at) {
        return user != null && Boolean.TRUE.equals(user.getEnabled()) ? unlocked(user, at) : null;
    }

    private UserAccount unlocked(UserAccount user, Instant at) {
        return loginLocked(user, at) ? null : user;
    }

    boolean hasLocalPassword(UserAccount user) {
        return user != null && effectivePasswordStatus(user) != PasswordStatus.NONE
                && user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
    }

    public UserAccount requireActiveUser(String username) {
        return requireActiveUser(TenantContext.currentTenantId().orElse(null), username);
    }

    public UserAccount requireActiveUser(String tenantId, String username) {
        UserAccount user = findUserForLogin(tenantId, username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthenticationFailedException("invalid username or password");
        }
        return user;
    }

    /** Finds the account named by a login attempt; callers must not treat its presence as authorization. */
    public UserAccount findUserForLogin(String tenantId, String username) {
        String validUsername = requireUsername(username);
        return findOne(Criteria.of()
                .eq("username", validUsername)
                .eqNullable("tenantId", normalizeBlank(tenantId)));
    }

    /** Spends one password verification without consulting a stored credential; see {@link PasswordHashingService#spendVerification}. */
    public void spendPasswordVerification(String password) {
        passwordHashingService.spendVerification(password);
    }

    public boolean passwordMatches(UserAccount user, String password) {
        return user != null && passwordHashingService.matches(password, user.getPasswordHash());
    }

    private void rejectDuplicateUsername(UserAccount user) {
        rejectDuplicate(user, Criteria.of()
                        .eq("username", user.getUsername())
                        .eqNullable("tenantId", user.getTenantId()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "iam.rejection.user-account.username-must-be-unique-within-tenant",
                                "username must be unique within tenant: {value1}"), Map.of("value1", String.valueOf(user.getUsername()))));
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
                    throw BusinessExceptions.warningText("iam.user.password-admin-current-user",
                TextReference.code("iam.user.password-admin-current-user", "不能由管理员重置当前登录用户的密码，请使用修改本人密码"));
                });
    }

    private void rejectCurrentUserForceLogout(String userId) {
        CurrentUserContext.currentUser()
                .filter(currentUser -> currentUser.userId().equals(userId))
                .ifPresent(currentUser -> {
                    throw BusinessExceptions.warningText("iam.user.force-logout-current-user",
                TextReference.code("iam.user.force-logout-current-user", "不能强制当前登录用户下线"));
                });
    }

    private net.ximatai.muyun.spring.common.i18n.TextReference selfAdministrationReason(String actionCode) {
        if ("forceLogout".equals(actionCode)) {
            return net.ximatai.muyun.spring.common.i18n.TextReference.code("iam.user.self-logout-unavailable", "不能强制当前登录用户下线");
        }
        return net.ximatai.muyun.spring.common.i18n.TextReference.code("iam.user.self-reset-unavailable", "不能由管理员重置当前登录用户的密码，请使用修改本人密码");
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
        retainCommandFields(user, existing, "passwordHash", "passwordStatus", "passwordChangedAt",
                "passwordExpiresAt", "lastLoginAt", "lastLoginIp", "lastLoginUserAgent",
                "lastFailedLoginAt", "failedLoginCount", "lockedUntil");
    }

    private void validatePasswordPolicy(String password) {
        if (passwordPolicyRuleService != null) {
            passwordPolicyRuleService.validatePassword(password);
            return;
        }
        if (password == null || password.length() < 6) {
            throw BusinessExceptions.warningText("iam.user.password-policy-violated",
                TextReference.code("iam.user.password-policy-violated", "密码长度不能少于 6 位"));
        }
    }

    private ActionExecutionPolicy currentRecordMutationPolicy() {
        return ActionExecutionContextHolder.current()
                .filter(context -> MODULE_ALIAS.equals(context.moduleAlias()))
                .map(context -> context.actionPolicy())
                .orElse(CHANGE_PASSWORD_POLICY);
    }

    private ActionExecutionPolicy ownPasswordPolicy() {
        return new ActionExecutionPolicy("changeOwnPassword", PlatformActionLevel.RECORD,
                ActionAccessMode.LOGIN_REQUIRED, false, false, ActionDefaultGrantPolicy.NONE, null);
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
        throw BusinessExceptions.warningText("iam.user.temporary-password-unavailable",
                TextReference.code("iam.user.temporary-password-unavailable", "无法生成符合当前密码策略的临时密码"));
    }

    private boolean passwordExpired(UserAccount user, Instant now) {
        return user != null
                && user.getPasswordExpiresAt() != null
                && now != null
                && !now.isBefore(user.getPasswordExpiresAt());
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
