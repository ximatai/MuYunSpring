package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.tenant.TenantLoginMethod;
import net.ximatai.muyun.spring.iam.tenant.TenantLoginMethodContributor;
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
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs external sign-in and binding as server-side transactions.
 *
 * <p>The browser starts a transaction and keeps its {@code state} and proof. The provider returns that
 * browser to the workbench, which submits the callback parameters together with both values; only then does
 * the platform verify the identity, check the tenant policy and enterprise, resolve the binding inside the
 * tenant and, for sign-in, open the session. A browser that did not start the transaction cannot complete
 * it, so an authorization link handed to someone else never yields a session or binding for its sender.</p>
 *
 * @hidden Internal IAM collaborator behind the {@code /iam.auth/external} HTTP entries.
 */
@Service
@ConditionalOnExternalIdentityFederation
public class ExternalLoginService implements TenantLoginMethodContributor {
    /** Binding and unbinding require a session opened within this window. */
    public static final Duration RECENT_AUTHENTICATION_WINDOW = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(ExternalLoginService.class);
    private static final int SECRET_BYTES = 32;
    private static final PageRequest FIRST = new PageRequest(0, 1);

    private final IdentityProviderService providers;
    private final TenantIdentityPolicyService policies;
    private final ExternalIdentityService identities;
    private final ExternalLoginTransactionDao transactionDao;
    private final UserAccountService userAccountService;
    private final UserSessionService userSessionService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final ExternalIdentityFederationSettings settings;
    private final Clock clock;
    private final ExternalIdentityAdmissionLocks admissionLocks;
    private DirectorySyncConfiguration directory;
    private DirectorySourceLifecycle sourceLifecycle;

    @Autowired
    void configureSourceStatus(DirectorySyncConfiguration directory, DirectorySourceLifecycle sourceLifecycle) {
        this.directory = directory;
        this.sourceLifecycle = sourceLifecycle;
    }

    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public ExternalLoginService(IdentityProviderService providers,
                                TenantIdentityPolicyService policies,
                                ExternalIdentityService identities,
                                ExternalLoginTransactionDao transactionDao,
                                UserAccountService userAccountService,
                                UserSessionService userSessionService,
                                ActiveTenantVerifier activeTenantVerifier,
                                ExternalIdentityFederationSettings settings,
                                ExternalIdentityAdmissionLocks admissionLocks) {
        this(providers, policies, identities, transactionDao, userAccountService, userSessionService,
                activeTenantVerifier, settings, Clock.systemUTC(), admissionLocks);
    }

    ExternalLoginService(IdentityProviderService providers,
                         TenantIdentityPolicyService policies,
                         ExternalIdentityService identities,
                         ExternalLoginTransactionDao transactionDao,
                         UserAccountService userAccountService,
                         UserSessionService userSessionService,
                         ActiveTenantVerifier activeTenantVerifier,
                         ExternalIdentityFederationSettings settings,
                         Clock clock,
                         ExternalIdentityAdmissionLocks admissionLocks) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.transactionDao = Objects.requireNonNull(transactionDao, "transactionDao");
        this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
        this.userSessionService = Objects.requireNonNull(userSessionService, "userSessionService");
        this.activeTenantVerifier = Objects.requireNonNull(activeTenantVerifier, "activeTenantVerifier");
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(settings.apiBaseUrl(), "muyun.iam.federation.api-base-url is required");
        Objects.requireNonNull(settings.workbenchUrl(), "muyun.iam.federation.workbench-url is required");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admissionLocks = Objects.requireNonNull(admissionLocks, "admissionLocks");
    }

    /** Providers a tenant's sign-in entry offers; empty for an unknown or inactive tenant. */
    @Override
    public List<TenantLoginMethod> loginMethods(String tenantId) {
        String tenant = normalize(tenantId);
        if (tenant == null || !tenantActive(tenant)) {
            return List.of();
        }
        return policies.enabledPolicies(tenant).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getLoginEnabled()))
                .map(policy -> providers.findUsable(policy.getProviderId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(provider -> providers.protocolOf(provider) != null)
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
        if (providers.protocolOf(provider) == null || !Boolean.TRUE.equals(policy.getLoginEnabled())) {
            throw unavailable();
        }
        return start(ExternalLoginPurpose.LOGIN, tenant, provider, null, null);
    }

    public ExternalLoginResults.Start startBind(CurrentUser currentUser, String sessionToken, String providerId) {
        String tenant = requireTenantUser(currentUser);
        requireRecentAuthentication(sessionToken);
        String sessionId = userSessionService.currentSessionId(sessionToken).orElseThrow(ExternalLoginService::reauthenticationRequired);
        IdentityProvider provider = providers.findUsable(providerId).orElseThrow(ExternalLoginService::unavailable);
        TenantIdentityPolicy policy = policies.enabledPolicy(tenant, provider.getId()).orElseThrow(ExternalLoginService::unavailable);
        if (providers.protocolOf(provider) == null || !Boolean.TRUE.equals(policy.getBindingEnabled())) {
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
        return start(ExternalLoginPurpose.BIND, tenant, provider, currentUser.userId(), sessionId);
    }

    /**
     * Completes a transaction from the browser that started it, with the parameters the provider returned to
     * the workbench. The state and proof must both match before the provider is contacted, and each
     * transaction completes at most once.
     */
    public ExternalLoginResults.Completion complete(String providerId, String state, String browserProof,
                                                    Map<String, String> callbackParameters, String ip,
                                                    String userAgent) {
        Instant now = clock.instant();
        Map<String, String> parameters = callbackParameters == null ? Map.of() : callbackParameters;
        // A provider that echoes the state must echo this transaction's state; anything else was not returned to
        // the browser that started it.
        if (parameters.containsKey("state") && !Objects.equals(parameters.get("state"), state)) {
            throw stateInvalid();
        }
        ExternalLoginTransaction transaction = state == null || state.isBlank() ? null : findByState(state);
        if (transaction == null || !transaction.getProviderId().equals(providerId) || browserProof == null
                || !MessageDigest.isEqual(hash(browserProof).getBytes(StandardCharsets.UTF_8),
                transaction.getBrowserProofHash().getBytes(StandardCharsets.UTF_8))) {
            throw stateInvalid();
        }
        ExternalLoginTransaction claimed = VersionedRecordMutation.update(transactionDao,
                () -> pending(transactionDao.findById(transaction.getId())),
                draft -> draft.setCompletedAt(now));
        if (claimed == null) {
            throw stateInvalid();
        }
        Outcome outcome;
        LoginResult login = null;
        try {
            outcome = now.isBefore(claimed.getExpiresAt())
                    ? resolve(claimed, state, parameters, ip, userAgent)
                    : Outcome.of(ExternalLoginStatus.EXPIRED, null);
            login = outcome.login();
        } catch (RuntimeException exception) {
            // A claimed transaction must always reach a final state; platform faults are not provider faults.
            log.warn("External login transaction {} failed during completion", claimed.getId(), exception);
            outcome = Outcome.denied(ExternalLoginFailure.INTERNAL_ERROR);
            login = null;
        }
        Outcome resolved = outcome;
        VersionedRecordMutation.update(transactionDao, () -> transactionDao.findById(claimed.getId()), draft -> {
            draft.setStatus(resolved.status());
            draft.setPkceVerifier(null);
            draft.setFailure(resolved.failure());
            draft.setResultUserId(resolved.userId());
            draft.setResultIdentityId(resolved.identityId());
        });
        ExternalLoginResults.IdentityView binding = resolved.status() == ExternalLoginStatus.SUCCEEDED
                && claimed.getPurpose() == ExternalLoginPurpose.BIND
                ? identityView(claimed.getTenantId(), resolved.identityId())
                : null;
        return new ExternalLoginResults.Completion(resolved.status(), claimed.getPurpose(), resolved.failure(),
                login, binding);
    }

    public ExternalLoginResults.OwnIdentities ownIdentities(CurrentUser currentUser) {
        String tenant = requireTenantUser(currentUser);
        List<ExternalIdentity> bindings;
        try (TenantContext.Scope ignored = TenantContext.use(tenant)) {
            bindings = identities.bindingsOfUser(currentUser.userId());
        }
        List<ExternalLoginResults.IdentityView> views = bindings.stream().map(this::identityView).toList();
        List<TenantLoginMethod> bindable = policies.enabledPolicies(tenant).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getBindingEnabled()))
                .filter(policy -> bindings.stream().noneMatch(binding -> binding.getProviderId().equals(policy.getProviderId())))
                .map(policy -> providers.findUsable(policy.getProviderId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(provider -> providers.protocolOf(provider) != null)
                .map(ExternalLoginService::loginMethod)
                .toList();
        return new ExternalLoginResults.OwnIdentities(views, bindable);
    }

    /**
     * Removes one of the current user's bindings, keeping at least one way to sign in: a local password or
     * another binding whose provider and tenant policy still allow signing in. Unbinds of one account
     * are serialized so two concurrent requests cannot both remove what they each believed was not the last.
     */
    @Transactional
    public void unbindOwn(CurrentUser currentUser, String sessionToken, String bindingId) {
        String tenant = requireTenantUser(currentUser);
        requireRecentAuthentication(sessionToken);
        try (TenantContext.Scope ignored = TenantContext.use(tenant)) {
            PlatformAbilityRuntime.lockMutationPartition("iam.external_identity.user",
                    tenant.length() + ":" + tenant + currentUser.userId());
            List<ExternalIdentity> bindings = identities.bindingsOfUser(currentUser.userId());
            ExternalIdentity binding = bindings.stream().filter(candidate -> candidate.getId().equals(bindingId))
                    .findFirst()
                    .orElseThrow(() -> BusinessExceptions.warningText("iam.federation.binding-not-found",
                            TextReference.code("iam.federation.binding-not-found", "绑定不存在或已解除")));
            UserAccount user = userAccountService.select(currentUser.userId());
            boolean hasPassword = user != null && userAccountService.effectivePasswordStatus(user) != PasswordStatus.NONE;
            // Only bindings that can still sign in to this tenant keep the account reachable after the unbind.
            boolean otherSignInRemains = bindings.stream()
                    .filter(candidate -> !candidate.getId().equals(binding.getId()))
                    .anyMatch(candidate -> canSignIn(tenant, candidate.getProviderId()));
            if (!hasPassword && !otherSignInRemains) {
                throw BusinessExceptions.warningText("iam.federation.last-sign-in-method", TextReference.code(
                        "iam.federation.last-sign-in-method", "这是账号唯一的登录方式，请先设置密码或绑定其他身份后再解除"));
            }
            identities.delete(binding.getId());
        }
    }

    private boolean canSignIn(String tenant, String providerId) {
        return providers.findUsable(providerId).filter(provider -> providers.protocolOf(provider) != null).isPresent() && policies.enabledPolicy(tenant, providerId)
                .map(policy -> Boolean.TRUE.equals(policy.getLoginEnabled()))
                .orElse(false);
    }

    private ExternalLoginResults.Start start(ExternalLoginPurpose purpose, String tenant, IdentityProvider provider,
                                             String initiatorUserId, String initiatorSessionId) {
        Instant now = clock.instant();
        String state = newSecret();
        String browserProof = newSecret();
        String codeVerifier = newSecret();
        ExternalLoginTransaction transaction = new ExternalLoginTransaction();
        transaction.setId(Ids.newId());
        transaction.setTenantId(tenant);
        transaction.setStateHash(hash(state));
        transaction.setBrowserProofHash(hash(browserProof));
        transaction.setPkceVerifier(codeVerifier);
        transaction.setPurpose(purpose);
        transaction.setProviderId(provider.getId());
        transaction.setInitiatorUserId(initiatorUserId);
        transaction.setInitiatorSessionId(initiatorSessionId);
        transaction.setStatus(ExternalLoginStatus.PENDING);
        transaction.setExpiresAt(now.plus(settings.transactionTtl()));
        EntityLifecycle.prepareInsert(transaction, now);
        transactionDao.insert(transaction);
        URI authorizationUri = providers.protocolOf(provider)
                .authorizationUri(providers.descriptorOf(provider), context(state, codeVerifier));
        return new ExternalLoginResults.Start(transaction.getId(), state, authorizationUri.toString(), browserProof,
                transaction.getExpiresAt());
    }

    private Outcome resolve(ExternalLoginTransaction transaction, String state, Map<String, String> parameters,
                            String ip, String userAgent) {
        Optional<IdentityProvider> usable = providers.findUsable(transaction.getProviderId());
        Optional<TenantIdentityPolicy> policy = policies.enabledPolicy(transaction.getTenantId(), transaction.getProviderId());
        if (usable.isEmpty() || policy.isEmpty() || !tenantActive(transaction.getTenantId())) {
            return Outcome.denied(ExternalLoginFailure.POLICY_DISABLED);
        }
        IdentityProvider provider = usable.get();
        boolean allowed = transaction.getPurpose() == ExternalLoginPurpose.LOGIN
                ? Boolean.TRUE.equals(policy.get().getLoginEnabled())
                : Boolean.TRUE.equals(policy.get().getBindingEnabled());
        if (!allowed) {
            return Outcome.denied(ExternalLoginFailure.POLICY_DISABLED);
        }
        IdentityProviderDescriptor descriptor = providers.descriptorOf(provider);
        VerifiedExternalIdentity identity;
        try {
            identity = providers.protocolOf(provider).verify(descriptor, Map.copyOf(parameters),
                    context(state, transaction.getPkceVerifier()));
        } catch (ExternalIdentityVerificationException exception) {
            log.warn("External identity verification failed for provider {}: {}", provider.getId(),
                    exception.getMessage());
            return Outcome.denied(exception.providerUnavailable()
                    ? ExternalLoginFailure.PROVIDER_UNAVAILABLE
                    : ExternalLoginFailure.PROVIDER_REJECTED);
        }
        if (descriptor.externalEnterpriseId() != null
                && !descriptor.externalEnterpriseId().equals(identity.externalEnterpriseId())) {
            return Outcome.denied(ExternalLoginFailure.ENTERPRISE_MISMATCH);
        }
        // Freeze the identity observation before I/O; an intervening sync must win over this snapshot.
        ExternalIdentity observed;
        try (TenantContext.Scope ignored = TenantContext.use(transaction.getTenantId())) {
            observed = identities.findByExternalIdentity(descriptor.identityNamespace(),
                    identity.externalEnterpriseId(), identity.externalUserId()).orElse(null);
        }
        ExternalDirectoryProtocol.Person refreshed = null;
        Instant checkedAt = clock.instant();
        if (transaction.getPurpose() == ExternalLoginPurpose.LOGIN && observed != null
                && sourceExpired(observed, policy.get(), checkedAt)) {
            try {
                refreshed = directory.protocol(descriptor).person(descriptor, identity.externalUserId());
                if (!identity.externalUserId().equals(refreshed.id())
                        || !java.util.Set.of("active", "disabled", "departed").contains(refreshed.status()))
                    return Outcome.denied(ExternalLoginFailure.PROVIDER_REJECTED);
            } catch (RuntimeException unavailable) {
                return Outcome.denied(ExternalLoginFailure.PROVIDER_UNAVAILABLE);
            }
        }
        var authoritative = refreshed;
        // All final facts below are re-read under locks, with no external calls inside this transaction.
        return identities.inAdmissionTransaction(() -> {
            identities.lockSource(transaction.getTenantId(), transaction.getProviderId());
            if (!admissionLocks.configuration(transaction.getTenantId(), transaction.getProviderId())) {
                return Outcome.denied(ExternalLoginFailure.POLICY_DISABLED);
            }
            Optional<IdentityProvider> latestProvider = providers.findUsable(transaction.getProviderId());
            Optional<TenantIdentityPolicy> latestPolicy = policies.enabledPolicy(
                    transaction.getTenantId(), transaction.getProviderId());
            if (latestProvider.isEmpty() || latestPolicy.isEmpty() || !tenantActive(transaction.getTenantId())
                    || !descriptor.sameAuthority(providers.descriptorOf(latestProvider.get()))
                    || !(transaction.getPurpose() == ExternalLoginPurpose.LOGIN
                    ? Boolean.TRUE.equals(latestPolicy.get().getLoginEnabled())
                    : Boolean.TRUE.equals(latestPolicy.get().getBindingEnabled()))) {
                return Outcome.denied(ExternalLoginFailure.POLICY_DISABLED);
            }
            try (TenantContext.Scope ignored = TenantContext.use(transaction.getTenantId())) {
                boolean lockedBinding = admissionLocks.identity(transaction.getTenantId(), descriptor.identityNamespace(), identity);
                Optional<ExternalIdentity> existing = lockedBinding ? identities.findByExternalIdentity(descriptor.identityNamespace(),
                        identity.externalEnterpriseId(), identity.externalUserId()) : Optional.empty();
                if (transaction.getPurpose() == ExternalLoginPurpose.BIND) {
                    var initiator = admissionLocks.initiator(transaction.getTenantId(), transaction.getInitiatorUserId(),
                            transaction.getInitiatorSessionId());
                    if (!initiator.account()) return Outcome.denied(ExternalLoginFailure.ACCOUNT_UNAVAILABLE);
                    if (!initiator.session()) return Outcome.denied(ExternalLoginFailure.SESSION_ENDED);
                    return resolveBind(transaction, descriptor, identity, existing);
                }
                if (existing.isPresent()) {
                    var binding = existing.get();
                    if (authoritative != null && observed != null
                            && Objects.equals(binding.getVersion(), observed.getVersion())) {
                        binding.setSourceStatus(authoritative.status());
                        binding.setSourceCheckedAt(checkedAt);
                        identities.update(binding);
                        if (!"active".equals(authoritative.status()))
                            sourceLifecycle.invalidate(binding, authoritative.status(), binding.getLastSyncRunId());
                    }
                    if (sourceExpired(binding, latestPolicy.get(), clock.instant())
                            || !sourceAllowsLogin(binding)
                            || !admissionLocks.employeeAvailable(transaction.getTenantId(), binding.getUserId()))
                        return Outcome.denied(ExternalLoginFailure.ACCOUNT_UNAVAILABLE);
                }
                Outcome outcome = resolveLogin(existing);
                if (outcome.status() != ExternalLoginStatus.SUCCEEDED) return outcome;
                LoginResult login = signIn(transaction, outcome, ip, userAgent, clock.instant());
                return login == null ? Outcome.denied(ExternalLoginFailure.ACCOUNT_UNAVAILABLE)
                        : new Outcome(outcome.status(), outcome.failure(), outcome.userId(), outcome.identityId(), login);
            }
        });
    }

    private static boolean sourceExpired(ExternalIdentity binding, TenantIdentityPolicy policy, Instant now) {
        Instant checked = binding.getSourceCheckedAt() == null ? binding.getBoundAt() : binding.getSourceCheckedAt();
        int seconds = policy.getSourceStatusMaxAgeSeconds() == null ? 86400 : policy.getSourceStatusMaxAgeSeconds();
        return checked == null || !now.isBefore(checked.plusSeconds(seconds));
    }

    private static boolean sourceAllowsLogin(ExternalIdentity binding) {
        return "active".equals(binding.getSourceStatus())
                || (binding.getBindingSource() == ExternalIdentityBindingSource.SELF_BIND
                    && binding.getSourceStatus() == null);
    }

    private Outcome resolveLogin(Optional<ExternalIdentity> existing) {
        // Provisioning other than DISABLED is not supported yet, so an unbound identity cannot sign in.
        return existing
                .map(binding -> new Outcome(ExternalLoginStatus.SUCCEEDED, null, binding.getUserId(), binding.getId()))
                .orElseGet(() -> Outcome.denied(ExternalLoginFailure.IDENTITY_NOT_BOUND));
    }

    private Outcome resolveBind(ExternalLoginTransaction transaction, IdentityProviderDescriptor provider,
                                VerifiedExternalIdentity identity, Optional<ExternalIdentity> existing) {
        String userId = transaction.getInitiatorUserId();
        UserAccount user = userAccountService.select(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return Outcome.denied(ExternalLoginFailure.ACCOUNT_UNAVAILABLE);
        }
        // The initiating session must remain live until the binding is persisted.
        if (!userSessionService.sessionActiveFor(userId, transaction.getInitiatorSessionId())) {
            return Outcome.denied(ExternalLoginFailure.SESSION_ENDED);
        }
        if (existing.isPresent()) {
            return existing.get().getUserId().equals(userId)
                    ? new Outcome(ExternalLoginStatus.SUCCEEDED, null, userId, existing.get().getId())
                    : Outcome.denied(ExternalLoginFailure.IDENTITY_CONFLICT);
        }
        if (identities.bindingsOfUser(userId).stream().anyMatch(binding -> provider.id().equals(binding.getProviderId()))) {
            return Outcome.denied(ExternalLoginFailure.ALREADY_BOUND);
        }
        ExternalIdentity binding = new ExternalIdentity();
        binding.setUserId(userId);
        binding.setProviderId(provider.id());
        binding.setIdentityNamespace(provider.identityNamespace());
        binding.setExternalEnterpriseId(identity.externalEnterpriseId());
        binding.setExternalUserId(identity.externalUserId());
        binding.setDisplayName(identity.displayName());
        binding.setBindingSource(ExternalIdentityBindingSource.SELF_BIND);
        binding.setBoundAt(clock.instant());
        try {
            identities.insert(binding);
        } catch (PlatformException exception) {
            if (!PlatformErrorCodes.CONFLICT_UNIQUE.equals(exception.code())) {
                throw exception;
            }
            // A concurrent bind of the same identity or provider lost the unique-index race.
            return Outcome.denied(ExternalLoginFailure.IDENTITY_CONFLICT);
        }
        return new Outcome(ExternalLoginStatus.SUCCEEDED, null, userId, binding.getId());
    }

    /** Opens the session; {@code null} when the account can no longer sign in. */
    private LoginResult signIn(ExternalLoginTransaction transaction, Outcome outcome, String ip, String userAgent,
                               Instant at) {
        LoginResult login;
        try {
            login = userSessionService.loginWithVerifiedIdentity(transaction.getTenantId(), outcome.userId(),
                    new AuthenticationMethod("external:" + transaction.getProviderId()),
                    transaction.getProviderId() + ":" + outcome.identityId(), ip, userAgent);
        } catch (AuthenticationFailedException exception) {
            return null;
        }
        try (TenantContext.Scope ignored = TenantContext.use(transaction.getTenantId())) {
            identities.recordAuthentication(outcome.identityId(), at);
        }
        return login;
    }

    private ExternalAuthorizationContext context(String state, String codeVerifier) {
        return new ExternalAuthorizationContext(state, codeVerifier, settings.workbenchUrl(), settings.apiBaseUrl());
    }

    private ExternalLoginTransaction findByState(String state) {
        return transactionDao.query(Criteria.of().eq("stateHash", hash(state)), FIRST).stream().findFirst().orElse(null);
    }

    private static ExternalLoginTransaction pending(ExternalLoginTransaction transaction) {
        return transaction != null && transaction.getStatus() == ExternalLoginStatus.PENDING
                && transaction.getCompletedAt() == null ? transaction : null;
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
            throw reauthenticationRequired();
        }
    }

    private static BusinessException reauthenticationRequired() {
        return BusinessExceptions.warningText("iam.federation.reauthentication-required", TextReference.code(
                "iam.federation.reauthentication-required", "为保护账号安全，请重新登录后再操作"));
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

    private static TenantLoginMethod loginMethod(IdentityProvider provider) {
        return new TenantLoginMethod(provider.getId(), provider.getTitle(), provider.getProviderType());
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

    private record Outcome(ExternalLoginStatus status, ExternalLoginFailure failure, String userId, String identityId,
                           LoginResult login) {
        Outcome(ExternalLoginStatus status, ExternalLoginFailure failure, String userId, String identityId) {
            this(status, failure, userId, identityId, null);
        }
        static Outcome of(ExternalLoginStatus status, ExternalLoginFailure failure) {
            return new Outcome(status, failure, null, null);
        }

        static Outcome denied(ExternalLoginFailure failure) {
            return of(ExternalLoginStatus.DENIED, failure);
        }
    }
}
