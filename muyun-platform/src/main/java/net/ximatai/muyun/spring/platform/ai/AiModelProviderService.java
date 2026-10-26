package net.ximatai.muyun.spring.platform.ai;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataOptions;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Owns the trusted, global provider catalogue. */
@Service
public class AiModelProviderService extends StandardBusinessService<AiModelProvider> implements
        net.ximatai.muyun.spring.platform.localization.ConfiguredRecordTextAbility<AiModelProvider>,
        GlobalScopedAbility<AiModelProvider>,
        SoftDeleteAbility<AiModelProvider>,
        EnableAbility<AiModelProvider>,
        SortAbility<AiModelProvider>,
        ReferenceAbility<AiModelProvider>,
        InitialDataAbility<AiModelProvider>,
        QueryAbility<AiModelProvider> {
    public static final String MODULE_ALIAS = "platform.ai_model_provider";
    public static final String DEEPSEEK_ID = "deepseek";
    public static final String BAILIAN_ID = "bailian";
    public static final String LM_STUDIO_ID = "lm_studio";

    public AiModelProviderService(BaseDao<AiModelProvider, String> dao) {
        super(MODULE_ALIAS, AiModelProvider.class, dao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, AiModelProvider.class,
                List.of("id", "title", "protocol", "baseUrl", "enabled", "sortOrder", "createdAt", "updatedAt"),
                Sort.asc("sortOrder"));
    }

    @Override
    public void beforePrepareInsert(AiModelProvider provider) {
        requireSystemAdministrator();
        provider.setId(PlatformNameRules.requireIdentifier(provider.getId(), "aiModelProviderId"));
    }

    @Override
    public void beforeInsert(AiModelProvider provider) {
        requireSystemAdministrator();
        normalizeAndValidate(provider);
    }

    @Override
    public void beforeUpdate(AiModelProvider provider, AiModelProvider existing) {
        requireSystemAdministrator();
        if (existing == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.ai-model-provider.ai-model-provider-does-not-exist",
                "AI model provider does not exist"));
        normalizeAndValidate(provider);
    }

    @Override
    public void beforeDelete(String id) {
        requireSystemAdministrator();
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("platform.ai-model-providers", 24);
    }

    @Override
    public List<AiModelProvider> initialData() {
        return List.of(provider(DEEPSEEK_ID, "DeepSeek", "https://api.deepseek.com", 10),
                provider(BAILIAN_ID, "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", 20),
                provider(LM_STUDIO_ID, "LM Studio", "http://127.0.0.1:1234/v1", 30));
    }

    public AiModelProvider requireEnabled(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-provider.ai-model-provider-must-not-be-blank",
                    "AI model provider must not be blank"));
        }
        try (TenantContext.Scope ignored = TenantContext.system("resolve AI model provider")) {
            AiModelProvider provider = findOne(Criteria.of().eq("id", providerId.trim()));
            if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.ai-model-provider.ai-model-provider-is-unavailable",
                        "AI model provider is unavailable: {value1}"), Map.of("value1", String.valueOf(providerId)));
            }
            return provider;
        }
    }

    private static AiModelProvider provider(String id, String title, String baseUrl, int sortOrder) {
        AiModelProvider provider = new AiModelProvider();
        provider.setId(id);
        provider.setTitle(title);
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl(baseUrl);
        provider.setEnabled(Boolean.TRUE);
        provider.setSortOrder(sortOrder);
        return provider;
    }

    private void normalizeAndValidate(AiModelProvider provider) {
        provider.setTenantId(null);
        if (provider.getProtocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-provider.unsupported-ai-model-provider-protocol",
                    "unsupported AI model provider protocol: {value1}"), Map.of("value1", String.valueOf(provider.getProtocol())));
        }
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            provider.setBaseUrl(normalizeTrustedBaseUrl(provider.getBaseUrl()));
        }
        if (provider.getEnabled() == null) provider.setEnabled(Boolean.TRUE);
    }

    static String normalizeTrustedBaseUrl(String baseUrl) {
        final URI uri;
        try {
            uri = new URI(baseUrl.trim()).normalize();
        } catch (URISyntaxException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-provider.ai-model-provider-base-url-is-invalid",
                    "AI model provider base URL is invalid"));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(host)))
                || host == null || host.isBlank() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-provider.ai-model-provider-base-url-must-be-https-except-for-a-loopback",
                    "AI model provider base URL must be HTTPS, except for a loopback HTTP endpoint"));
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private static void requireSystemAdministrator() {
        if (!CurrentUserContext.isSystem()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-provider.ai-model-providers-are-only-manageable-by-platform-super-administrators",
                    "AI model providers are only manageable by platform super administrators"));
        }
    }

}
