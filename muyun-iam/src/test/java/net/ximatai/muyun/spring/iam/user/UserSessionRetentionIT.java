package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL session schema, queue execution and lease-protected batch commits. */
@Testcontainers
@RecordApplicationEvents
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = UserSessionRetentionIT.Application.class)
class UserSessionRetentionIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired JobService jobs;
    @Autowired JobPlanDao plans;
    @Autowired JobRunDao runs;
    @Autowired UserSessionDao sessions;
    @Autowired IDatabaseOperations<?> database;
    @Autowired DataSource source;
    @Autowired ApplicationEvents events;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("iam_user_session", "platform_job_run", "platform_job_plan"))
            jdbc.update("delete from " + table);
    }

    @Test void cleansUnvisitedIdleAndAbsoluteExpiryAndSummarizesWithoutReplayingEvents() {
        seed("idle", "tenant-a", -31, 1, null, null);
        seed("absolute", "tenant-b", 1, -31, null, null);
        // A late authentication observation must not restart the actual expiry retention window.
        seed("observed", null, -40, -35, -1, "session expired");
        seed("logout", "tenant-b", 1, 1, -31, "logout");
        seed("revoked", "tenant-a", 1, 1, -31, "user inactive");
        var run = execute();
        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(run.getResultCode()).isEqualTo("USER_SESSIONS_CLEANED");
        assertThat(run.getStatisticsJson()).contains("\"deleted\":5", "\"idleExpired\":2",
                "\"absoluteExpired\":1", "\"loggedOut\":1", "\"revoked\":1");
        assertThat(count()).isZero();
        assertThat(events.stream(UserSessionLifecycleEvent.class)).isEmpty();
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":0");
        assertThat(count()).isZero();
    }

    @Test void retainsRecentEndedAndActiveSessionsAcrossEveryAccountPartition() {
        for (String tenant : new String[]{null, "tenant-a", "tenant-b"}) {
            String prefix = tenant == null ? "platform" : tenant;
            seed(prefix + "-idle", tenant, -29, 1, null, null);
            seed(prefix + "-absolute", tenant, 1, -29, null, null);
            seed(prefix + "-revoked", tenant, -60, -60, -29, "password changed");
            seed(prefix + "-active", tenant, 1, 2, null, null);
            seed(prefix + "-old", tenant, -31, 1, null, null);
        }
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":3");
        assertThat(count()).isEqualTo(12);
        for (String tenant : new String[]{"tenant-a", "tenant-b"}) {
            try (var scope = TenantContext.use(tenant)) {
                assertThat(new UserSessionRecordService(sessions, database).listByUserId("user"))
                        .hasSize(4).allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(tenant));
            }
        }
        assertThat(jdbc.queryForList("select id from iam_user_session where id like '%active'", String.class))
                .hasSize(3);
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":0");
    }

    @Test void boundedBatchesCommitPartialProgressAndNextExecutionDrainsRemainder() {
        for (int i = 0; i < 13; i++) seed("old-" + i, i % 2 == 0 ? "tenant-a" : "tenant-b", -31, 1, null, null);
        var first = execute();
        assertThat(first.getStatus()).isEqualTo("PARTIAL");
        assertThat(first.getResultCode()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(first.getStatisticsJson()).contains("\"deleted\":6", "\"batches\":2");
        assertThat(count()).isEqualTo(7);
        assertThat(execute().getStatus()).isEqualTo("PARTIAL");
        assertThat(count()).isEqualTo(1);
        assertThat(execute().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count()).isZero();
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":0");
    }

    @Test void skipsLockedSessionsAndCleansThemOnALaterRun() throws Exception {
        seed("locked", "tenant-a", -31, 1, null, null);
        seed("free", "tenant-b", -31, 1, null, null);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from iam_user_session where id = 'locked' for update")) {
                statement.executeQuery().close();
                assertThat(execute().getStatisticsJson()).contains("\"deleted\":1");
                assertThat(count()).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":1");
        assertThat(count()).isZero();
    }

    @Test void failedDeletionRollsBackTheWholeProtectedBatch() {
        seed("blocked", "tenant-a", -31, 1, null, null);
        seed("ordinary", "tenant-b", -31, 1, null, null);
        jdbc.execute("""
                create function reject_session_delete() returns trigger language plpgsql as $$
                begin raise exception 'test deletion failure'; end; $$
                """);
        jdbc.execute("create trigger reject_session_delete before delete on iam_user_session "
                + "for each row execute function reject_session_delete()");
        try {
            assertThat(execute().getStatus()).isEqualTo("FAILED");
            assertThat(count()).isEqualTo(2);
        } finally {
            jdbc.execute("drop trigger reject_session_delete on iam_user_session");
            jdbc.execute("drop function reject_session_delete()");
        }
        assertThat(execute().getStatisticsJson()).contains("\"deleted\":2");
    }

    @Test void defaultPlanUsesHourlyFixedDelayAndPreservesAdministratorChanges() {
        jobs.registerDefaults();
        var plan = plans.query(Criteria.of().eq("jobType", UserSessionRetentionJob.TYPE), PageRequest.of(1, 1)).getFirst();
        assertThat(Duration.between(plan.getCreatedAt(), plan.getNextFireAt()))
                .isBetween(Duration.ofSeconds(59), Duration.ofSeconds(61));
        jdbc.update("update platform_job_plan set next_fire_at = now() - interval '1 minute' where id = ?", plan.getId());
        jobs.scan();
        assertThat(jobs.runOne("default", "retention-node")).isTrue();
        var run = runs.query(Criteria.of().eq("planId", plan.getId()), PageRequest.of(1, 1)).getFirst();
        assertThat(Duration.between(run.getFinishedAt(), plans.findById(plan.getId()).getNextFireAt()))
                .isEqualTo(Duration.ofHours(1));
        jdbc.update("update platform_job_plan set enabled = false where id = ?", plan.getId());
        jobs.registerDefaults();
        assertThat(plans.findById(plan.getId()).getEnabled()).isFalse();
    }

    private JobRun execute() {
        JobRun queued;
        try (var scope = TenantContext.system("test session retention")) {
            queued = jobs.enqueue(UserSessionRetentionJob.TYPE, null, Map.of(), null);
        }
        assertThat(jobs.runOne("default", "retention-node")).isTrue();
        return runs.findById(queued.getId());
    }
    private int count() {
        return jdbc.queryForObject("select count(*) from iam_user_session", Integer.class);
    }
    private void seed(String id, String tenant, int idleDays, int absoluteDays, Integer revokedDays, String reason) {
        jdbc.update("""
                insert into iam_user_session(id, tenant_id, user_id, username, token_hash, issued_at,
                    expires_at, max_expires_at, revoked_at, revoked_reason)
                values (?, ?, 'user', 'user', md5(?), localtimestamp - interval '90 days',
                    localtimestamp + (? * interval '1 day'), localtimestamp + (? * interval '1 day'),
                    localtimestamp + (? * interval '1 day'), ?)
                """, id, tenant, id, idleDays, absoluteDays, revokedDays, reason);
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {JobPlanDao.class, UserSessionDao.class})
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegers() {
            return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
                @Override protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
        @Bean JobRegistry registry(IDatabaseOperations<?> db) {
            return new JobRegistry(List.of(new UserSessionRetentionJob(db, new UserSessionRetentionPolicy(30, 3, 2))));
        }
        @Bean JobService jobs(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> db,
                              PlatformTransactionManager transactions, JobRegistry registry,
                              ObjectProvider<JobFailureRecipientResolver> recipients,
                              ObjectProvider<BusinessNotificationDelivery> notifications,
                              ObjectProvider<JobManagementAccess> access) {
            return new JobService(plans, runs, db, transactions, registry, recipients, notifications, access,
                    Duration.ofMinutes(1), 90);
        }
    }
}
