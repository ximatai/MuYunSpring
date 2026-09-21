package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidate;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidateQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import net.ximatai.muyun.spring.ability.logging.LogText;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PostgresBusinessLogStoreIT.TestApplication.class)
class PostgresBusinessLogStoreIT extends PlatformPostgresIntegrationTest {

    @Autowired
    private PostgresBusinessLogStore store;

    @Autowired
    private PostgresBusinessLogSchemaInitializer schemaInitializer;

    @BeforeEach
    void ensureSchema() throws Exception {
        schemaInitializer.ensure();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("delete from muyun_log.business_log_event where event_id like 'retention-%'");
            statement.executeUpdate("""
                    update muyun_log.business_log_retention_policy
                    set automatic_cleanup_enabled = false,
                        retention_days = 180,
                        updated_at = null,
                        updated_by = null
                    """);
        }
    }

    @Test
    void shouldAppendIgnoreDuplicateBatchAndReadByOperationalFilters() {
        LoginLogEvent login = new LoginLogEvent(context("business-log-login", "trace-login", "tenant-a", "iam.login", "login", "2026-09-11T01:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, "127.0.0.1", "rui", "rui"));
        ActionLogEvent action = new ActionLogEvent(context("business-log-action", "trace-action", "tenant-a", "sales.contract", "submit", "2026-09-11T01:01:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 42L, 3L, null,
                        LogText.of("submitted"), "contract", "contract-1", RuntimeMutationSource.ACTION));
        RequestErrorLogEvent error = new RequestErrorLogEvent(context("business-log-error", "trace-error", "tenant-a", "sales.contract", "submit", "2026-09-11T01:02:00Z"),
                new RequestErrorLogDetails("POST", "/contracts", "sales.contract.submit", 12, 409, "CONTRACT_CONFLICT",
                        LogText.of("token=should-not-persist"), IllegalStateException.class.getName(),
                        LogText.of("password=should-not-persist"), LogText.of("Cookie: should-not-persist"), "controller", true));
        PageAccessLogEvent page = new PageAccessLogEvent(context("business-log-page", "trace-page", "tenant-b", "sales.contract", null, "2026-09-11T01:03:00Z"),
                new PageAccessLogDetails("contract-list", "sales-contract-menu", "menu"));

        assertThat(store.append(login).status()).isEqualTo(BusinessLogWriteResult.Status.APPENDED);
        assertThat(store.append(login).status()).isEqualTo(BusinessLogWriteResult.Status.DUPLICATE_IGNORED);
        assertThat(store.appendAll(List.of(action, error, page))).extracting(BusinessLogWriteResult::status)
                .containsOnly(BusinessLogWriteResult.Status.APPENDED);

        var errorPage = store.read(new BusinessLogQuery(null, null, "tenant-a", "sales.contract", "submit",
                "CONTRACT_CONFLICT", null, 10));
        assertThat(errorPage.events()).singleElement().isInstanceOfSatisfying(RequestErrorLogEvent.class, persisted -> {
            assertThat(persisted.eventId()).isEqualTo("business-log-error");
            assertThat(persisted.details().responseSummary().value()).contains("[REDACTED]")
                    .doesNotContain("should-not-persist");
            assertThat(persisted.details().stackTrace().value()).contains("[REDACTED]")
                    .doesNotContain("should-not-persist");
        });

        var successfulLogins = store.read(new BusinessLogQuery(null, null, "tenant-a",
                Set.of(BusinessLogEventType.LOGIN), null, null, null, null, null,
                LoginLogDetails.LoginOutcome.SUCCESS, null, null, 10));
        assertThat(successfulLogins.events()).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-login");
        var conflictResponses = store.read(new BusinessLogQuery(null, null, "tenant-a",
                Set.of(BusinessLogEventType.REQUEST_ERROR), null, null, null, null, null,
                null, 409, null, 10));
        assertThat(conflictResponses.events()).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-error");

        var attributedActions = store.read(new BusinessLogQuery(null, null, "tenant-a",
                Set.of(BusinessLogEventType.ACTION), null, null, "sales.contract", "submit", null,
                null, null, null, ActionLogDetails.ActionOutcome.SUCCESS, "contract-1",
                RuntimeMutationSource.ACTION, null, 10));
        assertThat(attributedActions.events()).singleElement().isInstanceOfSatisfying(ActionLogEvent.class, persisted -> {
            assertThat(persisted.details().entityAlias()).isEqualTo("contract");
            assertThat(persisted.details().recordId()).isEqualTo("contract-1");
            assertThat(persisted.details().mutationSource()).isEqualTo(RuntimeMutationSource.ACTION);
        });

        var loginsByAccount = store.read(new BusinessLogQuery(null, null, "tenant-a",
                Set.of(BusinessLogEventType.LOGIN), null, null, null, null, null,
                "rui", LoginLogDetails.LoginOutcome.SUCCESS, null, null, 10));
        assertThat(loginsByAccount.events()).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-login");

        var firstPage = store.read(new BusinessLogQuery(null, null, null, "sales.contract", null, null, null, 1));
        assertThat(firstPage.events()).singleElement().extracting(BusinessLogEvent::eventId).isEqualTo("business-log-page");
        assertThat(firstPage.nextCursor()).isNotNull();
        var secondPage = store.read(new BusinessLogQuery(null, null, null, "sales.contract", null, null,
                firstPage.nextCursor(), 10));
        assertThat(secondPage.events()).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-error", "business-log-action");
    }

    @Test
    void shouldNotExposeCursorWhenTheLastPageExactlyMatchesTheRequestedSize() {
        ActionLogEvent first = new ActionLogEvent(context("business-log-edge-1", "trace-edge-1", "tenant-a",
                "paging.edge", "query", "2026-09-11T02:00:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1, 1, null, null));
        ActionLogEvent second = new ActionLogEvent(context("business-log-edge-2", "trace-edge-2", "tenant-a",
                "paging.edge", "query", "2026-09-11T02:01:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1, 1, null, null));
        store.appendAll(List.of(first, second));

        var page = store.read(new BusinessLogQuery(null, null, null, "paging.edge", null, null, null, 2));

        assertThat(page.events()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldReadOneEventAndRestrictQueriesToEventTypeOperatorAndOrganizationScope() {
        ActionLogEvent organizationOneAction = new ActionLogEvent(context("business-log-org-action", "trace-org-action",
                "tenant-a", "user-1", "organization-1", "governance.query", "submit", "2026-09-11T03:00:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1L, 1L, null, null));
        ActionLogEvent organizationTwoAction = new ActionLogEvent(context("business-log-other-organization", "trace-other-org",
                "tenant-a", "user-1", "organization-2", "governance.query", "submit", "2026-09-11T03:01:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1L, 1L, null, null));
        LoginLogEvent anotherOperatorLogin = new LoginLogEvent(context("business-log-other-operator", "trace-other-user",
                "tenant-a", "user-2", "organization-1", "iam.login", "login", "2026-09-11T03:02:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));

        store.appendAll(List.of(organizationOneAction, organizationTwoAction, anotherOperatorLogin));

        var page = store.read(new BusinessLogQuery(null, null, "tenant-a", Set.of(BusinessLogEventType.ACTION),
                "user-1", Set.of("organization-1"), "governance.query", "submit", null, null, 10));

        assertThat(page.events()).extracting(BusinessLogEvent::eventId).containsExactly("business-log-org-action");
        assertThat(store.findById("business-log-org-action")).contains(organizationOneAction);
        assertThat(store.findById("missing-business-log-event")).isEmpty();
    }

    @Test
    void shouldIndexTheAuthenticationAccountWithoutConfusingItWithTheAuthenticatedOperator() {
        LoginLogEvent rejected = new LoginLogEvent(context("business-log-claimed-account", "trace-claimed",
                "tenant-a", "user-resolved-after-login", null, "iam.login", "login", "2026-09-11T04:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.FAILURE, "BAD_CREDENTIALS", null,
                        "attempted-account", null));
        LoginLogEvent confirmed = new LoginLogEvent(context("business-log-confirmed-account", "trace-confirmed",
                "tenant-a", "user-before-rename", null, "iam.login", "login", "2026-09-11T04:01:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null,
                        "submitted-alias", "canonical-account"));
        store.appendAll(List.of(rejected, confirmed));

        assertThat(loginEventsFor("attempted-account")).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-claimed-account");
        assertThat(loginEventsFor("canonical-account")).extracting(BusinessLogEvent::eventId)
                .containsExactly("business-log-confirmed-account");
        assertThat(loginEventsFor("user-resolved-after-login")).isEmpty();
        assertThat(store.read(new BusinessLogQuery(null, null, "tenant-a", Set.of(BusinessLogEventType.LOGIN),
                "user-resolved-after-login", null, null, null, null, null, null, null, null, 10)).events())
                .extracting(BusinessLogEvent::eventId).containsExactly("business-log-claimed-account");
    }

    @Test
    void shouldPurgeExpiredEventsInBoundedBatchesWithAnExclusiveCutoff() {
        LoginLogEvent firstExpired = new LoginLogEvent(context("retention-expired-1", "retention-trace-1",
                "retention-tenant", "iam.login", "login", "2000-01-01T00:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));
        LoginLogEvent secondExpired = new LoginLogEvent(context("retention-expired-2", "retention-trace-2",
                "retention-tenant", "iam.login", "login", "2000-01-02T00:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));
        LoginLogEvent thirdExpired = new LoginLogEvent(context("retention-expired-3", "retention-trace-3",
                "retention-tenant", "iam.login", "login", "2000-01-03T00:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));
        LoginLogEvent atCutoff = new LoginLogEvent(context("retention-at-cutoff", "retention-trace-4",
                "retention-tenant", "iam.login", "login", "2001-01-01T00:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));
        store.appendAll(List.of(firstExpired, secondExpired, thirdExpired, atCutoff));
        Instant cutoff = Instant.parse("2001-01-01T00:00:00Z");

        BusinessLogRetentionResult bounded = store.purge(new BusinessLogRetentionRequest(
                cutoff, Set.of(BusinessLogEventType.LOGIN), 1, 2));

        assertThat(bounded.deletedCount()).isEqualTo(2);
        assertThat(bounded.executedBatches()).isEqualTo(2);
        assertThat(bounded.status()).isEqualTo(BusinessLogRetentionResult.Status.BATCH_LIMIT_REACHED);
        BusinessLogRetentionResult completed = store.purge(new BusinessLogRetentionRequest(
                cutoff, Set.of(BusinessLogEventType.LOGIN), 1, 2));
        assertThat(completed.deletedCount()).isEqualTo(1);
        assertThat(completed.status()).isEqualTo(BusinessLogRetentionResult.Status.COMPLETE);
        assertThat(store.findById("retention-expired-1")).isEmpty();
        assertThat(store.findById("retention-expired-2")).isEmpty();
        assertThat(store.findById("retention-expired-3")).isEmpty();
        assertThat(store.findById("retention-at-cutoff")).contains(atCutoff);
    }

    @Test
    void shouldSkipRetentionWhenAnotherApplicationInstanceOwnsTheDatabaseLock() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var lock = connection.prepareStatement("select pg_advisory_lock(?)");
             var unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            lock.setLong(1, PostgresBusinessLogStore.RETENTION_ADVISORY_LOCK_KEY);
            lock.execute();
            try {
                BusinessLogRetentionResult result = store.purge(new BusinessLogRetentionRequest(
                        Instant.parse("2001-01-01T00:00:00Z"), 100, 1));

                assertThat(result.status()).isEqualTo(BusinessLogRetentionResult.Status.ALREADY_RUNNING);
                assertThat(result.deletedCount()).isZero();
                assertThat(result.executedBatches()).isZero();
            } finally {
                unlock.setLong(1, PostgresBusinessLogStore.RETENTION_ADVISORY_LOCK_KEY);
                unlock.execute();
            }
        }
    }

    @Test
    void shouldAllowFuturePoliciesToRetainDifferentLogTypesIndependently() {
        LoginLogEvent login = new LoginLogEvent(context("retention-scoped-login", "retention-scoped-trace-1",
                "retention-tenant", "iam.login", "login", "1999-01-01T00:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));
        ActionLogEvent action = new ActionLogEvent(context("retention-scoped-action", "retention-scoped-trace-2",
                "retention-tenant", "sales.contract", "submit", "1999-01-01T00:00:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", null, null, null, null));
        store.appendAll(List.of(login, action));

        BusinessLogRetentionResult result = store.purge(new BusinessLogRetentionRequest(
                Instant.parse("2000-01-01T00:00:00Z"), Set.of(BusinessLogEventType.LOGIN), 100, 1));

        assertThat(result.status()).isEqualTo(BusinessLogRetentionResult.Status.COMPLETE);
        assertThat(store.findById("retention-scoped-login")).isEmpty();
        assertThat(store.findById("retention-scoped-action")).contains(action);
    }

    @Test
    void shouldSeedAndPersistIndependentRuntimeRetentionPolicies() {
        assertThat(store.findRetentionPolicies())
                .extracting(BusinessLogRetentionPolicy::eventType)
                .containsExactlyInAnyOrder(BusinessLogEventType.LOGIN, BusinessLogEventType.ACTION,
                        BusinessLogEventType.REQUEST_ERROR, BusinessLogEventType.PAGE_ACCESS);

        BusinessLogRetentionPolicy updated = new BusinessLogRetentionPolicy(BusinessLogEventType.ACTION,
                true, 45, Instant.parse("2026-09-21T01:02:03Z"), "system-admin");

        assertThat(store.saveRetentionPolicy(updated)).isEqualTo(updated);
        assertThat(store.findRetentionPolicies())
                .filteredOn(policy -> policy.eventType() == BusinessLogEventType.ACTION)
                .containsExactly(updated);
        assertThat(store.findRetentionPolicies())
                .filteredOn(policy -> policy.eventType() == BusinessLogEventType.LOGIN)
                .singleElement()
                .extracting(BusinessLogRetentionPolicy::automaticCleanupEnabled,
                        BusinessLogRetentionPolicy::retentionDays)
                .containsExactly(false, 180);
    }

    @Test
    void shouldPageDistinctVisibleOperatorsByTheirEventTimeAccountSnapshot() {
        ActionLogEvent oldAccount = new ActionLogEvent(new BusinessLogContext("business-log-old-account",
                Instant.parse("2026-09-11T05:00:00Z"), Instant.parse("2026-09-11T05:00:01Z"), "trace-old",
                "tenant-a", "user-1", "old-account", "organization-1", "sales.order", "submit"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1L, 1L, null, null));
        ActionLogEvent currentAccount = new ActionLogEvent(new BusinessLogContext("business-log-current-account",
                Instant.parse("2026-09-11T05:01:00Z"), Instant.parse("2026-09-11T05:01:01Z"), "trace-current",
                "tenant-a", "user-1", "current-account", "organization-1", "sales.order", "submit"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1L, 1L, null, null));
        store.appendAll(List.of(oldAccount, currentAccount));

        var page = store.readOperatorCandidates(new BusinessLogQuery(null, null, "tenant-a",
                        Set.of(BusinessLogEventType.ACTION), null, Set.of("organization-1"), null, null,
                        null, null, null, null, null, 20),
                BusinessLogOperatorCandidateQuery.browse("current-account", new BusinessLogPageRequest(1, 20)));

        assertThat(page.candidates()).containsExactly(new BusinessLogOperatorCandidate("tenant-a", "user-1",
                "current-account", "organization-1", null));
    }

    @Test
    void shouldSafelyAddOrganizationAttributionToAnExistingLogTable() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("alter table muyun_log.business_log_event drop column if exists operator_account");
            statement.execute("alter table muyun_log.business_log_event drop column if exists operator_organization_id");
            statement.execute("alter table muyun_log.business_log_event drop column if exists login_outcome");
            statement.execute("alter table muyun_log.business_log_event drop column if exists login_account");
            statement.execute("alter table muyun_log.business_log_event drop column if exists http_status");
            statement.execute("alter table muyun_log.business_log_event drop column if exists action_outcome");
            statement.execute("alter table muyun_log.business_log_event drop column if exists entity_alias");
            statement.execute("alter table muyun_log.business_log_event drop column if exists record_id");
            statement.execute("alter table muyun_log.business_log_event drop column if exists mutation_source");
        }

        schemaInitializer.ensure();

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*) from information_schema.columns
                     where table_schema = 'muyun_log'
                       and table_name = 'business_log_event'
                       and column_name in ('operator_account', 'operator_organization_id', 'login_outcome', 'login_account',
                                           'http_status', 'action_outcome', 'entity_alias', 'record_id', 'mutation_source')
                     """);
             var resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(9);
        }
    }

    @Autowired
    private DataSource dataSource;

    private static BusinessLogContext context(String eventId, String traceId, String tenantId, String moduleAlias,
                                              String actionCode, String occurredAt) {
        return context(eventId, traceId, tenantId, "user-1", null, moduleAlias, actionCode, occurredAt);
    }

    private static BusinessLogContext context(String eventId, String traceId, String tenantId, String operatorId,
                                              String operatorOrganizationId, String moduleAlias, String actionCode,
                                              String occurredAt) {
        Instant occurred = Instant.parse(occurredAt);
        return new BusinessLogContext(eventId, occurred, occurred.plusSeconds(1), traceId, tenantId, operatorId,
                operatorOrganizationId, moduleAlias, actionCode);
    }

    private List<BusinessLogEvent> loginEventsFor(String account) {
        return store.read(new BusinessLogQuery(null, null, "tenant-a", Set.of(BusinessLogEventType.LOGIN),
                null, null, null, null, null, account, null, null, null, 10)).events();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
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
        PostgresBusinessLogStore postgresBusinessLogStore(DataSource dataSource) {
            return new PostgresBusinessLogStore(dataSource);
        }

        @Bean
        PostgresBusinessLogSchemaInitializer postgresBusinessLogSchemaInitializer(DataSource dataSource) {
            return new PostgresBusinessLogSchemaInitializer(dataSource);
        }
    }
}
