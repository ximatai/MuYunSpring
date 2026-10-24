package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.starter.bootstrap.MuYunSpringBootstrapConfiguration;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataBootstrapTask;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.web.PlatformMenuInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.module.StaticModuleDefinitionRegistrar;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.RoleActionDao;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.LoginLockoutPolicy;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryInitialDataDeclarations;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItem;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.menu.SystemMenuSchemeAccessPolicy;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MuYunSpringIdentityConfigurationTest {
    private final ApplicationContextRunner identityContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringIdentityConfiguration.class, MenuInitialDataAbilityConfiguration.class);
    private final ApplicationContextRunner bootstrapContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringBootstrapConfiguration.class,
                    MenuInitialDataAbilityConfiguration.class);

    @Test
    void shouldBindLoginLockoutPolicyFromProperties() {
        identityContextRunner.run(context -> {
            LoginLockoutPolicy policy = context.getBean(LoginLockoutPolicy.class);
            assertThat(policy.maxFailedAttempts()).isEqualTo(LoginLockoutPolicy.DEFAULT_MAX_FAILED_ATTEMPTS);
            assertThat(policy.lockDuration()).isEqualTo(LoginLockoutPolicy.DEFAULT_LOCK_DURATION);
        });
        identityContextRunner.withPropertyValues(
                        "muyun.iam.login.lockout.max-failed-attempts=0",
                        "muyun.iam.login.lockout.lock-duration=30m")
                .run(context -> {
                    LoginLockoutPolicy policy = context.getBean(LoginLockoutPolicy.class);
                    assertThat(policy.enabled()).isFalse();
                    assertThat(policy.lockDuration()).isEqualTo(java.time.Duration.ofMinutes(30));
                });
        identityContextRunner.withPropertyValues("muyun.iam.login.lockout.lock-duration=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldBindExternalIdentityFederationAddressesAndRejectUnsafeOnes() {
        identityContextRunner.withPropertyValues(
                        "muyun.iam.federation.api-base-url=https://platform.example.com/api",
                        "muyun.iam.federation.workbench-url=https://platform.example.com/app/",
                        "muyun.iam.federation.transaction-ttl=2m")
                .run(context -> {
                    var settings = context.getBean(
                            net.ximatai.muyun.spring.iam.federation.ExternalIdentityFederationSettings.class);
                    assertThat(settings.apiBaseUrl()).hasToString("https://platform.example.com/api");
                    assertThat(settings.workbenchUrl()).hasToString("https://platform.example.com/app/");
                    assertThat(settings.transactionTtl()).isEqualTo(java.time.Duration.ofMinutes(2));
                });
        identityContextRunner.withPropertyValues("muyun.iam.federation.workbench-url=javascript:alert(1)")
                .run(context -> assertThat(context).hasFailed());
        identityContextRunner.withPropertyValues("muyun.iam.federation.workbench-url=https://evil.example/?next=x")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldCollectMenuServicesAsInitialDataAbilities() {
        bootstrapContextRunner.run(context -> {
            Map<String, InitialDataAbility> abilities = context.getBeansOfType(InitialDataAbility.class);
            Map<String, PlatformBootstrapTask> bootstrapTasks = context.getBeansOfType(PlatformBootstrapTask.class);

            assertThat(abilities.values())
                    .anyMatch(MenuSchemeService.class::isInstance)
                    .anyMatch(MenuService.class::isInstance);
            assertThat(context).hasSingleBean(InitialDataExecutor.class);
            assertThat(context).hasSingleBean(PlatformMenuInitialDataDeclarationProvider.class);
            assertThat(context).hasSingleBean(DictionaryInitialDataDeclarations.class);
            assertThat(context).hasSingleBean(DictionaryFieldInitialDataDeclarationProvider.class);
            assertThat(bootstrapTasks.values())
                    .anyMatch(StaticModuleDefinitionRegistrar.class::isInstance)
                    .anyMatch(InitialDataBootstrapTask.class::isInstance);
        });
    }

    @Test
    void shouldUseTenantServiceAsActiveTenantVerifierWithoutExtraPrimaryAdapter() {
        identityContextRunner.run(context -> {
            Map<String, ActiveTenantVerifier> verifiers = context.getBeansOfType(ActiveTenantVerifier.class);

            assertThat(verifiers).containsKey("tenantService");
            assertThat(verifiers).containsKey("activeTenantVerifier");
            assertThat(context.getBean(ActiveTenantVerifier.class)).isSameAs(verifiers.get("activeTenantVerifier"));
        });
    }

    @Test
    void shouldBackOffDefaultActiveTenantVerifierWhenApplicationProvidesOne() {
        ActiveTenantVerifier customVerifier = tenantId -> {
        };

        identityContextRunner.withBean("customActiveTenantVerifier", ActiveTenantVerifier.class, () -> customVerifier,
                        beanDefinition -> beanDefinition.setPrimary(true))
                .run(context -> {
                    Map<String, ActiveTenantVerifier> verifiers = context.getBeansOfType(ActiveTenantVerifier.class);

                    assertThat(verifiers).containsEntry("customActiveTenantVerifier", customVerifier);
                    assertThat(verifiers).doesNotContainKey("activeTenantVerifier");
                    assertThat(context.getBean(ActiveTenantVerifier.class)).isSameAs(customVerifier);
                });
    }

    @Test
    void shouldNotFallbackTenantUsersToSystemMenuSchemeByDefault() {
        identityContextRunner.run(context -> {
            SystemMenuSchemeAccessPolicy policy = context.getBean(SystemMenuSchemeAccessPolicy.class);

            assertThat(policy.canUseSystemMenuScheme(CurrentUser.tenantUser(
                    UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID,
                    UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME,
                    "platform"))).isFalse();
            assertThat(policy.canUseSystemMenuScheme(CurrentUser.tenantUser(
                    UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID,
                    UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME,
                    "tenant-a"))).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class MenuInitialDataAbilityConfiguration {
        @Bean
        MenuSchemeService menuSchemeService() {
            return new MenuSchemeService(menuSchemeDao());
        }

        @Bean
        MenuService menuService(MenuSchemeService menuSchemeService, PlatformModuleService platformModuleService) {
            return new MenuService(menuDao(), menuSchemeService, platformModuleService, Optional.empty());
        }

        @Bean
        PlatformModuleService platformModuleService() {
            return mock(PlatformModuleService.class);
        }

        @Bean
        PlatformModuleActionService platformModuleActionService() {
            return mock(PlatformModuleActionService.class);
        }

        @Bean
        RoleService roleService() {
            return mock(RoleService.class);
        }

        @Bean
        AccountRoleGrantDao accountRoleGrantDao() {
            return mock(AccountRoleGrantDao.class);
        }

        @Bean
        EmploymentRoleGrantDao employmentRoleGrantDao() {
            return mock(EmploymentRoleGrantDao.class);
        }

        @Bean
        RoleActionDao roleActionDao() {
            return mock(RoleActionDao.class);
        }

        @Bean
        TenantService tenantService() {
            return mock(TenantService.class);
        }

        @Bean
        DictionaryCategoryService dictionaryCategoryService() {
            return new DictionaryCategoryService(dictionaryCategoryDao());
        }

        @Bean
        DictionaryItemService dictionaryItemService(DictionaryCategoryService dictionaryCategoryService) {
            return new DictionaryItemService(dictionaryItemDao(), dictionaryCategoryService);
        }

        @SuppressWarnings("unchecked")
        private BaseDao<MenuScheme, String> menuSchemeDao() {
            return mock(BaseDao.class);
        }

        @SuppressWarnings("unchecked")
        private BaseDao<Menu, String> menuDao() {
            return mock(BaseDao.class);
        }

        @SuppressWarnings("unchecked")
        private BaseDao<DictionaryCategory, String> dictionaryCategoryDao() {
            return mock(BaseDao.class);
        }

        @SuppressWarnings("unchecked")
        private BaseDao<DictionaryItem, String> dictionaryItemDao() {
            return mock(BaseDao.class);
        }
    }
}
