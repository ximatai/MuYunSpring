package net.ximatai.muyun.spring.starter.configuration.security;

import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MuYunSpringFieldProtectionConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringFieldProtectionConfiguration.class);

    @Test
    void createsFieldProtectionProvidersWhenTheMasterKeyIsConfigured() {
        contextRunner.withPropertyValues(
                        "muyun.security.field-protection.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                .run(context -> {
                    assertThat(context).hasSingleBean(FieldCryptoProvider.class);
                    assertThat(context).hasSingleBean(FieldSigner.class);
                });
    }

    @Test
    void leavesFieldProtectionUnavailableWhenTheMasterKeyIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(FieldCryptoProvider.class);
            assertThat(context).doesNotHaveBean(FieldSigner.class);
        });
    }
}
