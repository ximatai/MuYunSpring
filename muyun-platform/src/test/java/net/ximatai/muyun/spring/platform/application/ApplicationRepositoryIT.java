package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptDao;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ApplicationRepositoryIT.TestApplication.class)
class ApplicationRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired ApplicationService applications;
    @Autowired RecordSaveReceiptService receipts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @BeforeEach
    void setup() {
        var jdbc = new JdbcTemplate(source);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) {
                return new TransactionTemplate(transactions).execute(status -> work.get());
            }
            public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
    }

    @AfterEach
    void cleanup() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
    }

    @Test
    void retainedAliasConflictLeavesNoSaveReceiptAndCorrectedDraftCanBeSaved() {
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("builder", "Builder"));
             var scope = TenantContext.system("application save contract")) {
            String alias = "app_" + UUID.randomUUID().toString().replace("-", "");
            applications.insert(input(alias));
            assertThatThrownBy(() -> applications.insert(input(alias)))
                    .isInstanceOfSatisfying(PlatformException.class,
                            failure -> assertThat(failure.code()).isEqualTo(PlatformErrorCodes.CONFLICT_UNIQUE));
            applications.delete(alias);
            String request = UUID.randomUUID().toString();
            assertThatThrownBy(() -> save(request, alias))
                    .isInstanceOfSatisfying(PlatformException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT);
                        assertThat(failure.httpStatus()).isEqualTo(409);
                        assertThat(failure.targets()).extracting(target -> target.fieldName()).containsExactly("alias");
                    });
            assertThat(receipts.lookup(request, ApplicationService.MODULE_ALIAS)).isNull();
            assertThat(applications.select(alias)).isNull();
            assertThat(applications.selectIgnoreSoftDelete(alias).getDeleted()).isTrue();

            String corrected = alias + "_new";
            assertThat(save(request, corrected).getId()).isEqualTo(corrected);
            assertThat(receipts.lookup(request, ApplicationService.MODULE_ALIAS).getRecordId()).isEqualTo(corrected);
            assertThat(save(request, corrected).getId()).isEqualTo(corrected);
            assertThat(applications.selectIgnoreSoftDelete(alias).getDeleted()).isTrue();
        }
    }

    private Application save(String request, String alias) {
        return receipts.execute(request, ApplicationService.MODULE_ALIAS, "create",
                RecordSaveReceiptService.digest(alias), () -> {
                    applications.insert(input(alias));
                    return applications.select(alias);
                }, applications::select);
    }

    private Application input(String alias) {
        var application = new Application();
        application.setAlias(alias);
        application.setTitle("Training application");
        return application;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {ApplicationDao.class, RecordSaveReceiptDao.class})
    @Import({ApplicationService.class, RecordSaveReceiptService.class})
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
    }
}
