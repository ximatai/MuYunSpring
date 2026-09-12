package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
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
    void ensureSchema() {
        schemaInitializer.ensure();
    }

    @Test
    void shouldAppendIgnoreDuplicateBatchAndReadByOperationalFilters() {
        LoginLogEvent login = new LoginLogEvent(context("business-log-login", "trace-login", "tenant-a", "iam.login", "login", "2026-09-11T01:00:00Z"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null, "127.0.0.1", "rui", "rui"));
        ActionLogEvent action = new ActionLogEvent(context("business-log-action", "trace-action", "tenant-a", "sales.contract", "submit", "2026-09-11T01:01:00Z"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 42, 3, null, LogText.of("submitted")));
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
    void shouldSafelyAddOrganizationAttributionToAnExistingLogTable() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("alter table muyun_log.business_log_event drop column if exists operator_organization_id");
            statement.execute("alter table muyun_log.business_log_event drop column if exists login_outcome");
            statement.execute("alter table muyun_log.business_log_event drop column if exists login_account");
            statement.execute("alter table muyun_log.business_log_event drop column if exists http_status");
        }

        schemaInitializer.ensure();

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*) from information_schema.columns
                     where table_schema = 'muyun_log'
                       and table_name = 'business_log_event'
                       and column_name in ('operator_organization_id', 'login_outcome', 'login_account', 'http_status')
                     """);
             var resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(4);
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
