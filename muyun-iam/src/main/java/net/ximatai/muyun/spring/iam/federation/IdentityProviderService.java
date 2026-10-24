package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Platform catalogue of external identity providers; only platform super administrators maintain it. */
@Service
@ConditionalOnExternalIdentityFederation
public class IdentityProviderService extends StandardBusinessService<IdentityProvider> implements
        GlobalScopedAbility<IdentityProvider>,
        SoftDeleteAbility<IdentityProvider>,
        EnableAbility<IdentityProvider>,
        SortAbility<IdentityProvider>,
        ReferenceAbility<IdentityProvider>,
        QueryAbility<IdentityProvider> {
    public static final String MODULE_ALIAS = "iam.identity_provider";
    static final int MAX_ID_LENGTH = 48;
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final Set<String> installedProviderTypes;

    public IdentityProviderService(IdentityProviderDao dao, List<ExternalLoginProtocol> protocols) {
        super(MODULE_ALIAS, IdentityProvider.class, dao);
        this.installedProviderTypes = protocols.stream().map(ExternalLoginProtocol::providerType)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, IdentityProvider.class,
                List.of("id", "title", "providerType", "externalEnterpriseId", "enabled", "sortOrder",
                        "createdAt", "updatedAt"),
                Sort.asc("sortOrder"));
    }

    @Override
    public void beforePrepareInsert(IdentityProvider provider) {
        requireSystemAdministrator();
        String id = PlatformNameRules.requireIdentifier(provider.getId(), "identityProviderId");
        // The provider code is embedded in session login methods ("external:" + code), which hold 64 characters.
        if (id.length() > MAX_ID_LENGTH) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.provider-code-is-too-long",
                    "identity provider code must not exceed {value1} characters"),
                    Map.of("value1", String.valueOf(MAX_ID_LENGTH)));
        }
        provider.setId(id);
    }

    @Override
    public void beforeInsert(IdentityProvider provider) {
        requireSystemAdministrator();
        validate(provider);
    }

    @Override
    public void beforeUpdate(IdentityProvider provider, IdentityProvider existing) {
        requireSystemAdministrator();
        validate(provider);
    }

    @Override
    public void beforeDelete(String id) {
        requireSystemAdministrator();
    }

    /** Resolves an enabled provider whose protocol is installed, regardless of the caller's tenant. */
    public Optional<IdentityProvider> findUsable(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        try (TenantContext.Scope ignored = TenantContext.system("resolve external identity provider")) {
            IdentityProvider provider = findOne(Criteria.of().eq("id", providerId.trim()));
            return Optional.ofNullable(provider)
                    .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                    .filter(candidate -> installedProviderTypes.contains(candidate.getProviderType()));
        }
    }

    private void validate(IdentityProvider provider) {
        if (!installedProviderTypes.contains(provider.getProviderType())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.provider-type-is-not-installed",
                    "identity provider type is not installed: {value1}"),
                    Map.of("value1", String.valueOf(provider.getProviderType())));
        }
        String variable = provider.getClientSecretEnvironmentVariable();
        if (variable != null && !ENVIRONMENT_VARIABLE.matcher(variable).matches()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.client-secret-environment-variable-is-invalid",
                    "client secret environment variable name is invalid"), Map.of());
        }
    }

    private static void requireSystemAdministrator() {
        if (!CurrentUserContext.isSystem()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.only-platform-super-administrators-can-manage",
                    "identity providers are only manageable by platform super administrators"), Map.of());
        }
    }
}
