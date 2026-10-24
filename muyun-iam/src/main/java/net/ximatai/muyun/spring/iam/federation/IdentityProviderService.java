package net.ximatai.muyun.spring.iam.federation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
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
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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
    /** The provider code is embedded in session login methods ("external:" + code), which hold 64 characters. */
    static final int MAX_ID_LENGTH = 48;
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final Map<String, ExternalLoginProtocol> protocols;
    private final ExternalIdentityDao identityDao;
    private final Function<String, String> environment;

    @Autowired
    public IdentityProviderService(IdentityProviderDao dao, List<ExternalLoginProtocol> protocols,
                                   ExternalIdentityDao identityDao) {
        this(dao, protocols, identityDao, System::getenv);
    }

    IdentityProviderService(IdentityProviderDao dao, List<ExternalLoginProtocol> protocols,
                            ExternalIdentityDao identityDao, Function<String, String> environment) {
        super(MODULE_ALIAS, IdentityProvider.class, dao);
        this.protocols = protocols.stream().collect(Collectors.toUnmodifiableMap(
                ExternalLoginProtocol::providerType, Function.identity()));
        this.identityDao = Objects.requireNonNull(identityDao, "identityDao");
        this.environment = Objects.requireNonNull(environment, "environment");
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
        if (existing != null && hasBindings(existing.getId())
                && (!Objects.equals(existing.effectiveIdentityNamespace(), provider.effectiveIdentityNamespace())
                || !Objects.equals(existing.getExternalEnterpriseId(), provider.getExternalEnterpriseId()))) {
            // Existing bindings are keyed by namespace and enterprise; changing them would orphan every binding.
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.identity-scope-is-locked-by-bindings",
                    "identity namespace and enterprise cannot change while bindings exist"), Map.of());
        }
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
                    .filter(candidate -> protocols.containsKey(candidate.getProviderType()));
        }
    }

    /** The protocol installed for a usable provider. */
    ExternalLoginProtocol protocolOf(IdentityProvider provider) {
        return protocols.get(provider.getProviderType());
    }

    /** Read-only view handed to the provider's protocol, with the client secret resolved at call time. */
    IdentityProviderDescriptor descriptorOf(IdentityProvider provider) {
        String variable = provider.getClientSecretEnvironmentVariable();
        String secret = variable == null ? null : environment.apply(variable);
        return new IdentityProviderDescriptor(provider.getId(), provider.getProviderType(),
                provider.getExternalEnterpriseId(), provider.effectiveIdentityNamespace(), provider.getClientId(),
                secret == null || secret.isBlank() ? null : secret, configurationOf(provider.getConfiguration()));
    }

    private void validate(IdentityProvider provider) {
        ExternalLoginProtocol protocol = protocols.get(provider.getProviderType());
        if (protocol == null) {
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
        try {
            protocol.validateConfiguration(configurationOf(provider.getConfiguration()));
        } catch (IllegalArgumentException exception) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.identity-provider.configuration-is-invalid",
                    "identity provider configuration is invalid: {value1}"),
                    Map.of("value1", String.valueOf(exception.getMessage())));
        }
    }

    private boolean hasBindings(String providerId) {
        return !identityDao.query(Criteria.of().eq("providerId", providerId), new PageRequest(0, 1)).isEmpty();
    }

    private static Map<String, String> configurationOf(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> values = JSON.readValue(json, STRING_MAP);
            return values == null ? Map.of() : values;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("configuration must be a JSON object of string values");
        }
    }

    private static void requireSystemAdministrator() {
        if (!CurrentUserContext.isSystem()) {
            throw new PlatformAccessDeniedException(
                    "identity providers are only manageable by platform super administrators");
        }
    }
}
