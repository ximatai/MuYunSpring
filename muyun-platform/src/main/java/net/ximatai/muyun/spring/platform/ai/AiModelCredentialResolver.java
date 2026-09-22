package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
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

    public String resolve(AiModelConfiguration configuration) {
        String key;
        if (configuration.getCredentialSource() == AiModelCredentialSource.ENVIRONMENT) {
            String name = requireEnvironmentVariable(configuration.getApiKeyEnvironmentVariable());
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

    static String requireEnvironmentVariable(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.length() > 128) {
            throw new PlatformException("API Key 环境变量名须以字母或下划线开头，仅包含字母、数字和下划线，最长 128 个字符");
        }
        return name;
    }

    static void requireEnvironmentManagementAccess() {
        if (!CurrentUserContext.isSystem()) {
            throw new PlatformException("只有平台管理员可以配置或测试服务器环境变量凭据");
        }
    }
}
