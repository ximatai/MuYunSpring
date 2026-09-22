package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCredentialResolverTest {
    @Test
    void resolvesEnvironmentOnEveryInvocationWithoutMutatingTheConfiguration() {
        AtomicReference<String> key = new AtomicReference<>("first-secret");
        AiModelCredentialResolver resolver = new AiModelCredentialResolver(name -> key.get());
        AiModelConfiguration configuration = environment("MUYUN_AI_TEST_KEY");
        assertThat(resolver.resolve(configuration)).isEqualTo("first-secret");
        key.set("second-secret");
        assertThat(resolver.resolve(configuration)).isEqualTo("second-secret");
        assertThat(configuration.getApiKey()).isNull();
    }

    @Test
    void rejectsMissingVariablesAndInvalidNames() {
        AiModelCredentialResolver resolver = new AiModelCredentialResolver(name -> " ");
        assertThatThrownBy(() -> resolver.resolve(environment("MUYUN_AI_MISSING")))
                .hasMessageContaining("环境变量未设置或为空");
        for (String name : new String[]{"", "1KEY", "API-KEY", "API KEY", "${API_KEY}", "K".repeat(129)}) {
            assertThatThrownBy(() -> resolver.resolve(environment(name))).hasMessageContaining("环境变量名须");
        }
    }

    @Test
    void acceptsVariableNamesWithoutARequiredPrefixAndPreservesCase() {
        for (String name : new String[]{"DASHSCOPE_API_KEY", "OPENAI_API_KEY", "apiKey", "_KEY", "MUYUN_AI_KEY"}) {
            AiModelCredentialResolver resolver = new AiModelCredentialResolver(requested -> {
                assertThat(requested).isEqualTo(name);
                return "test-secret";
            });
            assertThat(resolver.resolve(environment(name))).isEqualTo("test-secret");
        }
    }

    @Test
    void onlySystemUsersMayConfigureOrProbeEnvironmentCredentials() {
        assertThatThrownBy(AiModelCredentialResolver::requireEnvironmentManagementAccess)
                .hasMessageContaining("只有平台管理员");
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("u", "u", "tenant"))) {
            assertThatThrownBy(AiModelCredentialResolver::requireEnvironmentManagementAccess)
                    .hasMessageContaining("只有平台管理员");
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("root", "root"))) {
            AiModelCredentialResolver.requireEnvironmentManagementAccess();
        }
    }

    private static AiModelConfiguration environment(String name) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setCredentialSource(AiModelCredentialSource.ENVIRONMENT);
        configuration.setApiKeyEnvironmentVariable(name);
        return configuration;
    }
}
