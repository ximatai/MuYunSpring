package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinition;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionRegistrar;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationDao;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBaselineInitialDataTest {
    private final ApplicationMemoryDao applicationDao = new ApplicationMemoryDao();
    private final TenantMemoryDao tenantDao = new TenantMemoryDao();
    private final UserAccountMemoryDao userAccountDao = new UserAccountMemoryDao();

    private final ApplicationService applicationService = new ApplicationService(applicationDao);
    private final TenantService tenantService = new TenantService(tenantDao);
    private final UserAccountService userAccountService = net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.create(
            userAccountDao, tenantService, new PasswordHashingService());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldInitializePlatformBaselineDataIdempotently() {
        initializeBaseline();
        initializeBaseline();

        assertThat(applicationDao.list(Criteria.of()))
                .extracting(Application::getAlias)
                .contains("platform", "iam");

        assertThat(tenantDao.list(Criteria.of())).isEmpty();
        try (TenantContext.Scope ignored = TenantContext.system("baseline admin verification")) {
            UserAccount user = userAccountService.select(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID);
            assertThat(user).isNotNull();
            assertThat(user.getTenantId()).isNull();
            assertThat(userAccountService.passwordMatches(user, "admin123")).isTrue();
        }
    }

    private void initializeBaseline() {
        new StaticApplicationDefinitionRegistrar(applicationService, List.of(
                StaticApplicationDefinition.of("platform", "平台能力", 10),
                StaticApplicationDefinition.of("iam", "身份权限", 20)
        )).registerAll();
        new InitialDataExecutor(
                List.<InitialDataAbility<?>>of(
                        userAccountService
                ),
                List.of()
        ).initializeAll();
    }

    private static class ApplicationMemoryDao extends TestMemoryDao<Application> implements ApplicationDao {
    }

    private static class TenantMemoryDao extends TestMemoryDao<Tenant> implements TenantDao {
    }

    private static class UserAccountMemoryDao extends TestMemoryDao<UserAccount> implements UserAccountDao {
    }
}
