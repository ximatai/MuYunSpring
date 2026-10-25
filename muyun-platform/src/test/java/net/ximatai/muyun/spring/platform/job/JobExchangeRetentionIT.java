package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeReceiptDao;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeReceipts;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeRetention;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** The real queue, guarded batch transactions and receipt repository share the same database. */
@SpringBootTest(classes = JobExchangeRetentionIT.Application.class)
class JobExchangeRetentionIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired JobService jobs;
    @Autowired JobPlanDao plans;
    @Autowired JobRunDao runs;
    @Autowired DataSource source;

    @Test
    void scheduledCleanupKeepsInitialDelayHourlyCompletionDelayAndTenBatchesOfTwoHundred() {
        var jdbc = new JdbcTemplate(source);
        jobs.registerDefaults();
        var plan = plans.query(Criteria.of().eq("jobType", "platform.exchange-retention"), PageRequest.of(1, 1)).getFirst();
        assertThat(plan.getNextFireAt()).isAfter(plan.getCreatedAt());
        assertThat(Duration.between(plan.getCreatedAt(), plan.getNextFireAt())).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(61));
        jdbc.update("""
                insert into platform_record_exchange_receipt(id, document_id, module_alias, purpose, expires_at)
                select md5('job-exchange-' || n), 'job-exchange', 'test.job_exchange_retention', 'export', timestamp '1900-01-01'
                from generate_series(1, 2001) n
                """);
        jdbc.update("""
                insert into platform_record_exchange_receipt(id, document_id, module_alias, purpose, expires_at)
                values(md5('job-exchange-future'), 'job-exchange', 'test.job_exchange_retention', 'export', now() + interval '1 day')
                """);
        jdbc.update("update platform_job_plan set next_fire_at = now() - interval '1 minute' where id = ?", plan.getId());
        jobs.scan();
        assertThat(jobs.runOne("default", "exchange-first-node")).isTrue();
        var first = runs.query(Criteria.of().eq("planId", plan.getId()), PageRequest.of(1, 10)).getFirst();
        assertThat(first.getStatus()).isEqualTo("PARTIAL");
        assertThat(first.getResultCode()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(first.getStatisticsJson()).contains("2000");
        assertThat(jdbc.queryForObject("select count(*) from platform_record_exchange_receipt where module_alias = 'test.job_exchange_retention'", Integer.class))
                .isEqualTo(2);
        plan = plans.findById(plan.getId());
        assertThat(Duration.between(first.getFinishedAt(), plan.getNextFireAt())).isEqualTo(Duration.ofHours(1));
        jdbc.update("update platform_job_plan set next_fire_at = now() - interval '1 minute' where id = ?", plan.getId());
        jobs.scan();
        assertThat(jobs.runOne("default", "exchange-second-node")).isTrue();
        assertThat(runs.query(Criteria.of().eq("planId", plan.getId()).eq("status", "SUCCEEDED"), PageRequest.of(1, 10)))
                .hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from platform_record_exchange_receipt where module_alias = 'test.job_exchange_retention'", Integer.class))
                .isEqualTo(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {JobPlanDao.class, RecordExchangeReceiptDao.class})
    static class Application {
        @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegerJdbiConfigurer() {
            // Production registers this in the starter; repository tests assemble the platform without it.
            return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
                @Override
                protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean RecordExchangeReceipts receipts(RecordExchangeReceiptDao dao) { return new RecordExchangeReceipts(dao); }
        @Bean JobRegistry registry(RecordExchangeReceipts receipts) {
            return new JobRegistry(List.of(new RecordExchangeRetention(receipts)));
        }
        @Bean JobService jobs(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> database,
                              PlatformTransactionManager transactions, JobRegistry registry,
                              ObjectProvider<JobFailureRecipientResolver> recipients,
                              ObjectProvider<BusinessNotificationDelivery> notifications,
                              ObjectProvider<JobManagementAccess> access) {
            return new JobService(plans, runs, database, transactions, registry, recipients, notifications, access,
                    Duration.ofMinutes(1), 90);
        }
    }
}
