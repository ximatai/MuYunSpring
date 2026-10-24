package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.AuthenticationMethod;
import net.ximatai.muyun.spring.iam.user.LoginResult;
import net.ximatai.muyun.spring.iam.user.PasswordStatus;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs external sign-in and binding as server-side transactions.
 *
 * <p>A transaction is created by the browser, completed once by the provider callback, and redeemed once by
 * the browser that holds its proof. The provider protocol only verifies who the user is; this service checks
 * the tenant policy and enterprise, resolves the binding inside the tenant, and opens the session through
 * the IAM session entry. Neither the state, the proof nor any token is ever carried in a redirect URL.</p>
 */
@Service
@ConditionalOnExternalIdentityFederation
public class ExternalLoginService {
    /** Binding and unbinding require a session opened within this window. */
    public static final Duration RECENT_AUTHENTICATION_WINDOW = Duration.ofMinutes(5);
    public static final String WORKBENCH_TENANT_PARAMETER = "_muyunTenantId";
    public static final String WORKBENCH_TRANSACTION_PARAMETER = "_muyunExternalLogin";
    private static final Logger log = LoggerFactory.getLogger(ExternalLoginService.class);
    private static final int SECRET_BYTES = 32;
    private static final PageRequest FIRST = new PageRequest(0, 1);

    private final IdentityProviderService providers;
    private final TenantIdentityPolicyService policies;
    private final ExternalIdentityService identities;
    private final ExternalIdentityDao identityDao;
    private final ExternalLoginTransactionDao transactionDao;
    private final UserAccountService userAccountService;
    private final UserSessionService userSessionService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final Map<String, ExternalLoginProtocol> protocols;
    private final ExternalIdentityFederationSettings settings;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public ExternalLoginService(IdentityProviderService providers,
                                TenantIdentityPolicyService policies,
                                ExternalIdentityService identities,
                                ExternalIdentityDao identityDao,
                                ExternalLoginTransactionDao transactionDao,
                                UserAccountService userAccountService,
                                UserSessionService userSessionService,
                                ActiveTenantVerifier activeTenantVerifier,
                                List<ExternalLoginProtocol> protocols,
                                ExternalIdentityFederationSettings settings) {
        this(providers, policies, identities, identityDao, transactionDao, userAccountService, userSessionService,
                activeTenantVerifier, protocols, settings, Clock.systemUTC());
    }

    ExternalLoginService(IdentityProviderService providers,
                         TenantIdentityPolicyService policies,
                         ExternalIdentityService identities,
                         ExternalIdentityDao identityDao,
                         ExternalLoginTransactionDao transactionDao,
                         UserAccountService userAccountService,
                         UserSessionService userSessionService,
                         ActiveTenantVerifier activeTenantVerifier,
                         List<ExternalLoginProtocol> protocols,
                         ExternalIdentityFederationSettings settings,
                         Clock clock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.identityDao = Objects.requireNonNull(identityDao, "identityDao");
        this.transactionDao = Objects.requireNonNull(transactionDao, "transactionDao");
        this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
        this.userSessionService = Objects.requireNonNull(userSessionService, "userSessionService");
        this.activeTenantVerifier = Objects.requireNonNull(activeTenantVerifier, "activeTenantVerifier");
        this.protocols = protocols.stream().collect(Collectors.toUnmodifiableMap(
                ExternalLoginProtocol::providerType, Function.identity()));
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(settings.apiBaseUrl(), "muyun.iam.federation.api-base-url is required");
        Objects.requireNonNull(settings.workbenchUrl(), "muyun.iam.federation.workbench-url is required");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Providers a tenant's sign-in entry offers; empty for an unknown or inactive tenant. */
    public List<ExternalLoginResults.LoginMethod> loginMethods(String tenantId) {
        String tenant = normalize(tenantId);
        if (tenant == null || !tenantActive(tenant)) {
            return List.of();
        }
        return policies.enabledPolicies(tenant).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getLoginEnabled()))
                .map(policy -> providers.findUsable(policy.getProviderId()).orElse(null))
                .filter(Objects::nonNull)
                .map(ExternalLoginService::loginMethod)
                .toList();
    }

    public ExternalLoginResults.Start startLogin(String tenantId, String providerId) {
        String tenant = normalize(tenantId);
        if (tenant == null || !tenantActive(tenant)) {
            throw unavailable();
        }
        IdentityProvider provider = providers.findUsable(providerId).orElseThrow(ExternalLoginService::unavailable);
        TenantIdentityPolicy policy = policies.enabledPolicy(tenant, provider.getId()).orElseThrow(ExternalLoginService::unavailable);
        if (!Boolean.TRUE.equals(policy.getLoginEnabled())) {
            throw unavailable();
        }
        return start(ExternalLoginPurpose.LOGIN, tenant, provider, null);
    }

    public ExternalLoginResults.Start startBind(CurrentUser currentUser, String sessionToken, String providerId) {
        String tenant = requireTenantUser(currentUser);
        requireRecentAuthentication(sessionToken);
        IdentityProvider provider = providers.findUsable(providerId).orElseThrow(ExternalLoginService::unavailable);
        TenantIdentityPolicy policy = policies.enabledPolicy(tenant, provider.getId()).orElseThrow(ExternalLoginService::unavailable);
        if (!Boolean.TRUE.equals(policy.getBindingEnabled())) {
            throw unavailable();
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenant)) {
            boolean bound = identities.bindingsOfUser(currentUser.userId()).stream()
                    .anyMatch(binding -> provider.getId().equals(binding.getProviderId()));
            if (bound) {
                throw BusinessExceptions.warningText("iam.federation.already-bound", TextReference.code(
                        "iam.federation.already-bound", "当前账号已绑定该身份源"));
            }
        }
        return start(ExternalLoginPurpose.BIND, tenant, provider, currentUser.userId());
    }

    /**
     * Completes a transaction from the provider callback and returns where the browser goes next. Every
     * outcome, including denials, is stored on the transaction for the browser to redeem; an unknown state
     * only returns the workbench address.
     */
    public URI complete(String providerId, Map<String, String> callbackParameters) {
        Instant now = clock.instant();
        String state = callbackParameters.get("state");
        ExternalLoginTransaction transaction = state == null || state.isBlank() ? null : findByState(state);
        if (transaction == null || !transaction.getProviderId().equals(providerId)) {
            return settings.workbenchUrl();
        }
        ExternalLoginTransaction claimed = VersionedRecordMutation.update(transactionDao,
                () -> pending(transactionDao.findById(transaction.getId())),
                draft -> draft.setCompletedAt(now));
        if (claimed == null) {
            return workbenchReturn(transaction);
        }
        Outcome outcome;
        try {
            outcome = now.isBefore(claimed.getExpiresAt())
                    ? resolve(claimed, state, callbackParameters)
                    : Outcome.of(ExternalLoginStatus.EXPIRED, null);
        } catch (RuntimeException exception) {
            // A claimed transaction must always reach a final state the browser can redeem.
            log.warn("External login transaction {} failed during completion", claimed.getId(), exception);
            outcome = Outcome.denied(ExternalLoginFailureCodes.PROVIDER_UNAVAILABLE);
        }
        Outcome resolved = outcome;
        VersionedRecordMutation.update(transactionDao, () -> transactionDao.findById(claimed.getId()), draft -> {
            draft.setStatus(resolved.status());
            draft.setFailureCode(resolved.failureCode());
            draft.setResultUserId(resolved.userId());
            draft.setResultIdentityId(resolved.identityId());
        });
        return workbenchReturn(claimed);
    }

    /**
     * Redeems a transaction once from the browser holding its proof. A pending transaction may be checked
     * again; a completed one is consumed by the first redemption.
     */
    public ExternalLoginResults.Redemption redeem(String transactionId, String browserProof, String ip,
                                                  String userAgent) {
        Instant now = clock.instant();
        ExternalLoginTransaction transaction = transactionId == null ? null : transactionDao.findById(transactionId);
        if (transaction == null || browserProof == null
                || !MessageDigest.isEqual(hash(browserProof).getBytes(StandardCharsets.UTF_8),
                transaction.getBrowserProofHash().getBytes(StandardCharsets.UTF_8))) {
            throw stateInvalid();
        }
        if (transaction.getStatus() == ExternalLoginStatus.PENDING && transaction.getCompletedAt() == null) {
            if (now.isBefore(transaction.getExpiresAt())) {
                return new ExternalLoginResults.Redemption(ExternalLoginStatus.PENDING, transaction.getPurpose(),
                        null, null, null);
            }
            return new ExternalLoginResults.Redemption(ExternalLoginStatus.EXPIRED, transaction.getPurpose(),
                    null, null, null);
        }
        if (!now.isBefore(transaction.getExpiresAt().plus(settings.transactionTtl()))) {
            throw stateInvalid();
        }
        ExternalLoginTransaction redeemed = VersionedRecordMutation.update(transactionDao, () -> {
            ExternalLoginTransaction latest = transactionDao.findById(transaction.getId());
            return latest != null && latest.getRedeemedAt() == null
                    && latest.getStatus() != ExternalLoginStatus.PENDING ? latest : null;
        }, draft -> draft.setRedeemedAt(now));
        if (redeemed == null) {
            throw stateInvalid();
        }
        if (redeemed.getStatus() != ExternalLoginStatus.SUCCEEDED) {
            return new ExternalLoginResults.Redemption(redeemed.getStatus(), redeemed.getPurpose(),
                    redeemed.getFailureCode(), null, null);
        }
        if (redeemed.getPurpose() == ExternalLoginPurpose.BIND) {
            return new ExternalLoginResults.Redemption(ExternalLoginStatus.SUCCEEDED, ExternalLoginPurpose.BIND, null,
                    null, identityView(redeemed.getTenantId(), redeemed.getResultIdentityId()));
        }
        LoginResult login = userSessionService.loginWithVerifiedIdentity(redeemed.getTenantId(),
                redeemed.getResultUserId(), authenticationMethod(redeemed.getProviderId()),
                redeemed.getProviderId() + ":" + redeemed.getResultIdentityId(), ip, userAgent);
        touchBinding(redeemed.getResultIdentityId(), now);
        return new ExternalLoginResults.Redemption(ExternalLoginStatus.SUCCEEDED, ExternalLoginPurpose.LOGIN, null,
                login, null);
    }

    public ExternalLoginResults.OwnIdentities ownIdentities(CurrentUser currentUser) {
        String tenant = requireTenantUser(currentUser);
        List<ExternalIdentity> bindings;
        try (TenantContext.Scope ignored = TenantContext.use(tenant)) {
            bindings = identities.bindingsOfUser(currentUser.userId());
        }
        List<ExternalLoginResults.IdentityView> views = bindings.stream().map(this::identityView).toList();
        List<ExternalLoginResults.LoginMethod> bindable = policies.enabledPolicies(tenant).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getBindingEnabled()))
                .filter(policy -> bindings.stream().noneMatch(binding -> binding.getProviderId().equals(policy.getProviderId())))
                .map(policy -> providers.findUsable(policy.getProviderId()).orElse(null))
                .filter(Objects::nonNull)
                .map(ExternalLoginService::loginMethod)
                .toList();
        return new ExternalLoginResults.OwnIdentities(views, bindable);
    }

    /** Removes one of the current user's bindings, keeping at least one way to sign in. */
    public void unbindOwn(CurrentUser currentUser, String sessionToken, String bindingId) {
        String tenant = requireTenantUser(currentUser);
        requireRecentAuthentication(sessionToken);
        try (TenantContext.Scope ignored = TenantContext.use(tenant)) {
            List<ExternalIdentity> bindings = identities.bindingsOfUser(currentUser.userId());
            ExternalIdentity binding = bindings.stream().filter(candidate -> candidate.getId().equals(bindingId))
                    .findFirst()
                    .orElseThrow(() -> BusinessExceptions.warningText("iam.federation.binding-not-found",
                            TextReference.code("iam.federation.binding-not-found", "绑定不存在或已解除")));
            UserAccount user = userAccountService.select(currentUser.userId());
            boolean hasPassword = user != null && userAccountService.effectivePasswordStatus(user) != PasswordStatus.NONE;
            if (!hasPassword && bindings.size() <= 1) {
                throw BusinessExceptions.warningText("iam.federation.last-sign-in-method", TextReference.code(
                        "iam.federation.last-sign-in-method", "这是账号唯一的登录方式，请先设置密码或绑定其他身份后再解除"));
            }
            identityDao.deleteById(binding.getId());
        }
    }

    private ExternalLoginResults.Start start(ExternalLoginPurpose purpose, String tenant, IdentityProvider provider,
                                             String initiatorUserId) {
        ExternalLoginProtocol protocol = protocols.get(provider.getProviderType());
        if (protocol == null) {
            throw unavailable();
        }
        Instant now = clock.instant();
        String state = newSecret();
        String browserProof = newSecret();
        ExternalLoginTransaction transaction = new ExternalLoginTransaction();
        transaction.setId(Ids.newId());
        transaction.setTenantId(tenant);
        transaction.setStateHash(hash(state));
        transaction.setBrowserProofHash(hash(browserProof));
        transaction.setPurpose(purpose);
        transaction.setProviderId(provider.getId());
        transaction.setInitiatorUserId(initiatorUserId);
        transaction.setStatus(ExternalLoginStatus.PENDING);
        transaction.setExpiresAt(now.plus(settings.transactionTtl()));
        EntityLifecycle.prepareInsert(transaction, now);
        transactionDao.insert(transaction);
        URI authorizationUri = protocol.authorizationUri(provider, context(provider, state));
        return new ExternalLoginResults.Start(transaction.getId(), authorizationUri.toString(), browserProof,
                transaction.getExpiresAt());
    }

    private Outcome resolve(ExternalLoginTransaction transaction, String state, Map<String, String> parameters) {
        Optional<IdentityProvider> usable = providers.findUsable(transaction.getProviderId());
        Optional<TenantIdentityPolicy> policy = policies.enabledPolicy(transaction.getTenantId(), transaction.getProviderId());
        if (usable.isEmpty() || policy.isEmpty() || !tenantActive(transaction.getTenantId())) {
            return Outcome.denied(ExternalLoginFailureCodes.POLICY_DISABLED);
        }
        IdentityProvider provider = usable.get();
        boolean allowed = transaction.getPurpose() == ExternalLoginPurpose.LOGIN
                ? Boolean.TRUE.equals(policy.get().getLoginEnabled())
                : Boolean.TRUE.equals(policy.get().getBindingEnabled());
        if (!allowed) {
            return Outcome.denied(ExternalLoginFailureCodes.POLICY_DISABLED);
        }
        VerifiedExternalIdentity identity;
        try {
            identity = protocols.get(provider.getProviderType())
                    .verify(provider, Map.copyOf(parameters), context(provider, state));
        } catch (ExternalIdentityVerificationException exception) {
            log.warn("External identity verification failed for provider {}: {}", provider.getId(),
                    exception.getMessage());
            return Outcome.denied(exception.providerUnavailable()
                    ? ExternalLoginFailureCodes.PROVIDER_UNAVAILABLE
                    : ExternalLoginFailureCodes.PROVIDER_REJECTED);
        }
        if (provider.getExternalEnterpriseId() != null
                && !provider.getExternalEnterpriseId().equals(identity.externalEnterpriseId())) {
            return Outcome.denied(ExternalLoginFailureCodes.ENTERPRISE_MISMATCH);
        }
        try (TenantContext.Scope ignored = TenantContext.use(transaction.getTenantId())) {
            Optional<ExternalIdentity> existing = identities.findByExternalIdentity(
                    provider.effectiveIdentityNamespace(), identity.externalEnterpriseId(), identity.externalUserId());
            return transaction.getPurpose() == ExternalLoginPurpose.LOGIN
                    ? resolveLogin(existing)
                    : resolveBind(transaction, provider, identity, existing);
        }
    }

    private Outcome resolveLogin(Optional<ExternalIdentity> existing) {
        // Provisioning other than DISABLED is not supported yet, so an unbound identity cannot sign in.
        return existing
                .map(binding -> new Outcome(ExternalLoginStatus.SUCCEEDED, null, binding.getUserId(), binding.getId()))
                .orElseGet(() -> Outcome.denied(ExternalLoginFailureCodes.IDENTITY_NOT_BOUND));
    }

    private Outcome resolveBind(ExternalLoginTransaction transaction, IdentityProvider provider,
                                VerifiedExternalIdentity identity, Optional<ExternalIdentity> existing) {
        String userId = transaction.getInitiatorUserId();
        if (existing.isPresent()) {
            return existing.get().getUserId().equals(userId)
                    ? new Outcome(ExternalLoginStatus.SUCCEEDED, null, userId, existing.get().getId())
                    : Outcome.denied(ExternalLoginFailureCodes.IDENTITY_CONFLICT);
        }
        UserAccount user = userAccountService.select(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return Outcome.denied(ExternalLoginFailureCodes.ACCOUNT_UNAVAILABLE);
        }
        if (identities.bindingsOfUser(userId).stream().anyMatch(binding -> provider.getId().equals(binding.getProviderId()))) {
            return Outcome.denied(ExternalLoginFailureCodes.ALREADY_BOUND);
        }
        ExternalIdentity binding = new ExternalIdentity();
        binding.setUserId(userId);
        binding.setProviderId(provider.getId());
        binding.setIdentityNamespace(provider.effectiveIdentityNamespace());
        binding.setExternalEnterpriseId(identity.externalEnterpriseId());
        binding.setExternalUserId(identity.externalUserId());
        binding.setDisplayName(identity.displayName());
        binding.setBindingSource(ExternalIdentityBindingSource.SELF_BIND);
        binding.setBoundAt(clock.instant());
        try {
            identities.insert(binding);
        } catch (PlatformException conflict) {
            // A concurrent bind of the same identity or provider lost the unique-index race.
            return Outcome.denied(ExternalLoginFailureCodes.IDENTITY_CONFLICT);
        }
        return new Outcome(ExternalLoginStatus.SUCCEEDED, null, userId, binding.getId());
    }

    private ExternalAuthorizationContext context(IdentityProvider provider, String state) {
        URI callback = ExternalIdentityUris.resolve(settings.apiBaseUrl(),
                "iam.auth/external/" + provider.getId() + "/callback");
        return new ExternalAuthorizationContext(state, callback, settings.apiBaseUrl());
    }

    private URI workbenchReturn(ExternalLoginTransaction transaction) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(WORKBENCH_TENANT_PARAMETER, transaction.getTenantId());
        parameters.put(WORKBENCH_TRANSACTION_PARAMETER, transaction.getId());
        return ExternalIdentityUris.withQuery(settings.workbenchUrl(), parameters);
    }

    private ExternalLoginTransaction findByState(String state) {
        return transactionDao.query(Criteria.of().eq("stateHash", hash(state)), FIRST).stream().findFirst().orElse(null);
    }

    private static ExternalLoginTransaction pending(ExternalLoginTransaction transaction) {
        return transaction != null && transaction.getStatus() == ExternalLoginStatus.PENDING
                && transaction.getCompletedAt() == null ? transaction : null;
    }

    private void touchBinding(String identityId, Instant at) {
        try {
            VersionedRecordMutation.update(identityDao, () -> identityDao.findById(identityId),
                    binding -> binding.setLastAuthenticatedAt(at));
        } catch (RuntimeException exception) {
            // The session is already issued; a failed usage stamp must not undo the sign-in.
            log.warn("Failed to record external identity usage for binding {}", identityId);
        }
    }

    private ExternalLoginResults.IdentityView identityView(String tenantId, String identityId) {
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return identityView(identities.select(identityId));
        }
    }

    private ExternalLoginResults.IdentityView identityView(ExternalIdentity binding) {
        String title = providers.findUsable(binding.getProviderId()).map(IdentityProvider::getTitle)
                .orElse(binding.getProviderId());
        return new ExternalLoginResults.IdentityView(binding.getId(), binding.getProviderId(), title,
                binding.getExternalUserId(), binding.getDisplayName(), binding.getBoundAt(),
                binding.getLastAuthenticatedAt());
    }

    private void requireRecentAuthentication(String sessionToken) {
        if (!userSessionService.authenticatedWithin(sessionToken, RECENT_AUTHENTICATION_WINDOW)) {
            throw BusinessExceptions.warningText("iam.federation.reauthentication-required", TextReference.code(
                    "iam.federation.reauthentication-required", "为保护账号安全，请重新登录后再操作"));
        }
    }

    private static String requireTenantUser(CurrentUser currentUser) {
        if (currentUser == null || currentUser.system() || currentUser.tenantId() == null) {
            throw BusinessExceptions.warningText("iam.federation.tenant-user-required", TextReference.code(
                    "iam.federation.tenant-user-required", "外部身份只能绑定到租户账号"));
        }
        return currentUser.tenantId();
    }

    private boolean tenantActive(String tenantId) {
        try {
            activeTenantVerifier.verifyActiveTenant(tenantId);
            return true;
        } catch (PlatformException exception) {
            return false;
        }
    }

    private static AuthenticationMethod authenticationMethod(String providerId) {
        return new AuthenticationMethod("external:" + providerId);
    }

    private static ExternalLoginResults.LoginMethod loginMethod(IdentityProvider provider) {
        return new ExternalLoginResults.LoginMethod(provider.getId(), provider.getTitle(), provider.getProviderType());
    }

    private static PlatformException unavailable() {
        return BusinessExceptions.warningText("iam.federation.provider-unavailable", TextReference.code(
                "iam.federation.provider-unavailable", "该登录方式当前不可用"));
    }

    private static PlatformException stateInvalid() {
        return BusinessExceptions.warningText("iam.federation.state-invalid", TextReference.code(
                "iam.federation.state-invalid", "登录请求已失效，请重新发起"));
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Outcome(ExternalLoginStatus status, String failureCode, String userId, String identityId) {
        static Outcome of(ExternalLoginStatus status, String failureCode) {
            return new Outcome(status, failureCode, null, null);
        }

        static Outcome denied(String failureCode) {
            return of(ExternalLoginStatus.DENIED, failureCode);
        }
    }
}
