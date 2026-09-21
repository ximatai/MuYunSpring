package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.RequestTenantVerifier;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MuYunSpringIdentityWebConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringIdentityWebConfiguration.class);

    @Test
    void shouldProvideWebIdentityContextWithoutSessionService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CurrentUserProvider.class);
            assertThat(context).hasSingleBean(CurrentUserWebFilter.class);
            assertThat(context).hasSingleBean(RequestTenantVerifier.class);
            assertThat(context).hasSingleBean(RequestTraceWebFilter.class);
            assertThat(context.getBean(CurrentUserProvider.class).currentUser()).isEmpty();
        });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesCurrentUserProvider() {
        CurrentUserProvider customProvider = Optional::empty;

        contextRunner.withBean(CurrentUserProvider.class, () -> customProvider)
                .run(context -> {
                    assertThat(context.getBean(CurrentUserProvider.class)).isSameAs(customProvider);
                    assertThat(context).hasSingleBean(CurrentUserWebFilter.class);
                    assertThat(context).hasSingleBean(RequestTraceWebFilter.class);
                });
    }

    @Test
    void shouldProvideBusinessRequestTenantVerifierWhenIamDependenciesAreAvailable() {
        TenantService tenantService = mock(TenantService.class);
        contextRunner
                .withBean(TenantService.class, () -> tenantService)
                .withBean(ActionExecutionPolicyService.class, () -> mock(ActionExecutionPolicyService.class))
                .run(context -> {
                    RequestTenantVerifier verifier = (RequestTenantVerifier) org.springframework.test.util
                            .ReflectionTestUtils.getField(context.getBean(CurrentUserWebFilter.class), "requestTenantVerifier");
                    assertThat(verifier).isNotNull();
                    try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                            CurrentUser.tenantUser("user-a", "User A", "tenant-a"))) {
                        verifier.verify("tenant-a");
                    }
                    verify(tenantService).verifyActiveTenant("tenant-a");
                });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesRequestTenantVerifier() {
        RequestTenantVerifier customVerifier = tenantId -> { };

        contextRunner
                .withBean(TenantService.class, () -> mock(TenantService.class))
                .withBean(ActionExecutionPolicyService.class, () -> mock(ActionExecutionPolicyService.class))
                .withBean(RequestTenantVerifier.class, () -> customVerifier)
                .run(context -> {
                    assertThat(context).hasSingleBean(RequestTenantVerifier.class);
                    assertThat(context.getBean(RequestTenantVerifier.class)).isSameAs(customVerifier);
                    assertThat(context.getBean(CurrentUserWebFilter.class))
                            .extracting("requestTenantVerifier")
                            .isSameAs(customVerifier);
                });
    }
}
