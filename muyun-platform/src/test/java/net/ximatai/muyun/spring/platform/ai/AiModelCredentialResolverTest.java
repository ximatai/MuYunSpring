package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCredentialResolverTest {
    private static final String BASE = "https://example.test/v1";
    private static final String NAME = "MUYUN_AI_TEST_API_KEY";

    @Test
    void resolvesEnvironmentOnEveryInvocationWithoutMutatingTheConfiguration() {
        AtomicReference<String> key = new AtomicReference<>("first-secret");
        AiModelCredentialResolver resolver = new AiModelCredentialResolver(name ->
                name.endsWith("_BASE_URL") ? BASE : key.get());
        AiModelConfiguration configuration = environment(NAME);
        assertThat(resolver.resolve(configuration, BASE)).isEqualTo("first-secret");
        key.set("second-secret");
        assertThat(resolver.resolve(configuration, BASE)).isEqualTo("second-secret");
        assertThat(configuration.getApiKey()).isNull();
    }

    @Test
    void rejectsNonDedicatedNamesBeforeReadingAnyEnvironmentVariable() {
        AiModelCredentialResolver resolver = new AiModelCredentialResolver(name -> {
            throw new AssertionError("Must not read environment");
        });
        for (String name : new String[]{"", "DATABASE_PASSWORD", "DASHSCOPE_API_KEY", "apiKey",
                "MUYUN_IDP_TEST_SECRET", "MUYUN_AI_KEY", "MUYUN_AI_TEST_API_KEY" + "K".repeat(129)}) {
            assertThatThrownBy(() -> resolver.resolve(environment(name), BASE)).hasMessageContaining("环境变量名须");
        }
    }

    @Test
    void requiresExactDeploymentBindingBeforeReadingSecret() {
        var reads = new java.util.ArrayList<String>();
        AiModelCredentialResolver resolver = new AiModelCredentialResolver(name -> {
            reads.add(name);
            return name.endsWith("_BASE_URL") ? BASE : "secret";
        });
        for (String target : new String[]{"https://attacker.test/v1", "https://example.test/other",
                "https://example.test:8443/v1", "http://127.0.0.1:1234/v1"}) {
            assertThatThrownBy(() -> resolver.resolve(environment(NAME), target)).hasMessageContaining("目标地址授权");
        }
        assertThat(reads).containsOnly(NAME + "_BASE_URL");
        assertThat(resolver.resolve(environment(NAME), BASE + "/")).isEqualTo("secret");
        assertThatThrownBy(() -> new AiModelCredentialResolver(name -> null)
                .resolve(environment(NAME), BASE)).hasMessageContaining("目标地址授权");
        assertThatThrownBy(() -> new AiModelCredentialResolver(name -> name.endsWith("_BASE_URL") ? BASE : " ")
                .resolve(environment(NAME), BASE)).hasMessageContaining("环境变量未设置或为空");
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
