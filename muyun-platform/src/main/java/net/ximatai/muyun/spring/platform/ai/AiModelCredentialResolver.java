package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/** Resolves credentials only at invocation time; resolved secrets never enter a stored entity. */
@Component
public class AiModelCredentialResolver {
    private final Function<String, String> environment;

    public AiModelCredentialResolver() {
        this(System::getenv);
    }

    AiModelCredentialResolver(Function<String, String> environment) {
        this.environment = environment;
    }

    public String resolve(AiModelConfiguration configuration, String baseUrl) {
        String key;
        if (configuration.getCredentialSource() == AiModelCredentialSource.ENVIRONMENT) {
            String name = requireAuthorizedReference(configuration, baseUrl);
            key = environment.apply(name);
            if (key == null || key.isBlank()) {
                throw new PlatformConfigurationException("AI API Key 环境变量未设置或为空：" + name);
            }
        } else {
            key = configuration.getApiKey();
            if (key == null || key.isBlank()) {
                throw new PlatformConfigurationException("AI model configuration has no API key");
            }
        }
        return key.trim();
    }

    /** Deployment owns the exact endpoint binding; management records cannot grant it. */
    String requireAuthorizedReference(AiModelConfiguration configuration, String baseUrl) {
        String name = requireEnvironmentVariable(configuration.getApiKeyEnvironmentVariable());
        String allowedBaseUrl = environment.apply(name + "_BASE_URL");
        if (allowedBaseUrl == null || allowedBaseUrl.isBlank() || baseUrl == null
                || !AiModelProviderService.normalizeTrustedBaseUrl(allowedBaseUrl).equals(
                        AiModelProviderService.normalizeTrustedBaseUrl(baseUrl))) {
            throw new PlatformConfigurationException("AI 环境变量凭据未获部署侧目标地址授权");
        }
        return name;
    }

    static String requireEnvironmentVariable(String name) {
        if (name == null || !name.matches("MUYUN_AI_[A-Z0-9_]+_API_KEY") || name.length() > 128) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-credential-resolver.api-key-environment-variable-must-use-dedicated-namespace",
                    "API Key 环境变量名须匹配 MUYUN_AI_[A-Z0-9_]+_API_KEY，最长 128 个字符"));
        }
        return name;
    }

    static void requireEnvironmentManagementAccess() {
        if (!CurrentUserContext.isSystem()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.ai-model-credential-resolver.only-platform-administrators-can-configure-or-test-server-environment-credentials",
                    "只有平台管理员可以配置或测试服务器环境变量凭据"));
        }
    }
}
