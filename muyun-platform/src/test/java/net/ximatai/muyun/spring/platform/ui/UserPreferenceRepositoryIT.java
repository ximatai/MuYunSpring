package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = UserPreferenceRepositoryIT.TestApplication.class)
class UserPreferenceRepositoryIT extends PlatformPostgresIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final UserPreferenceService service;
    private final UserPreferenceDao preferenceDao;

    @Autowired
    UserPreferenceRepositoryIT(UserPreferenceService service, UserPreferenceDao preferenceDao) {
        this.service = service;
        this.preferenceDao = preferenceDao;
    }

    @Test
    void shouldAtomicallyOverwriteTenantPreferenceUnderConcurrentWrites() throws Exception {
        assertAtomicOverwrite(CurrentUser.tenantUser("preference-user-tenant", "User", "tenant-it"));
    }

    @Test
    void shouldAtomicallyOverwriteSystemPreferenceUnderConcurrentWrites() throws Exception {
        assertAtomicOverwrite(CurrentUser.systemUser("preference-user-system", "System User"));
    }

    private void assertAtomicOverwrite(CurrentUser user) throws Exception {
        int writerCount = 12;
        CountDownLatch ready = new CountDownLatch(writerCount);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writerCount);
        try {
            List<Callable<UserPreference>> tasks = IntStream.range(0, writerCount)
                    .mapToObj(index -> (Callable<UserPreference>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try (TenantContext.Scope ignoredTenant = tenantScope(user);
                             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(user)) {
                            return service.saveCurrentUserPreference(
                                    PlatformUiClientType.WEB,
                                    "workbench.concurrent-test",
                                    "{\"writer\":" + index + "}");
                        }
                    })
                    .toList();
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<UserPreference> saved = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(saved).extracting(UserPreference::getId).containsOnly(saved.getFirst().getId());
            assertThat(preferenceDao.count(Criteria.of()
                    .eq("userId", user.userId())
                    .eq("clientType", PlatformUiClientType.WEB.name())
                    .eq("preferenceKey", "workbench.concurrent-test"))).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private TenantContext.Scope tenantScope(CurrentUser user) {
        return user.system()
                ? TenantContext.system("user preference concurrency test")
                : TenantContext.use(user.tenantId());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = UserPreferenceDao.class)
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        UserPreferenceService userPreferenceService(UserPreferenceDao preferenceDao) {
            return new UserPreferenceService(preferenceDao);
        }
    }
}
