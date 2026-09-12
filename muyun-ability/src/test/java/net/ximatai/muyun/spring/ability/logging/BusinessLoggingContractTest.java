package net.ximatai.muyun.spring.ability.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessLoggingContractTest {

    @Test
    void shouldExposeTypedFactsWithSharedCorrelationContext() {
        BusinessLogContext context = new BusinessLogContext(
                "login-1", Instant.parse("2026-09-11T01:00:00Z"), Instant.parse("2026-09-11T01:00:01Z"),
                "trace-1", "tenant-1", "user-1", "iam.login", "login");
        LoginLogEvent event = new LoginLogEvent(context, new LoginLogDetails(
                "password", LoginLogDetails.LoginOutcome.SUCCESS, null, "127.0.0.1", "rui", "rui"));

        assertThat(event.eventType()).isEqualTo(BusinessLogEventType.LOGIN);
        assertThat(event.eventId()).isEqualTo("login-1");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.details().outcome()).isEqualTo(LoginLogDetails.LoginOutcome.SUCCESS);
    }

    @Test
    void shouldUseEventIdAsCorrelationFallbackWhenTraceIdIsUnavailable() {
        BusinessLogContext context = new BusinessLogContext(
                "event-1", Instant.now(), Instant.now(), null, null, null, null, null);

        assertThat(context.traceId()).isEqualTo("event-1");
    }

    @Test
    void shouldKeepLegacyContextAndQueryConstructionCompatibleWithOrganizationFiltering() {
        BusinessLogContext legacy = new BusinessLogContext(
                "event-1", Instant.now(), Instant.now(), null, "tenant-1", "user-1", "sales.contract", "submit");
        BusinessLogQuery legacyQuery = new BusinessLogQuery(null, null, "tenant-1", "sales.contract", "submit",
                null, null, 20);
        BusinessLogQuery restrictedQuery = new BusinessLogQuery(null, null, "tenant-1", Set.of(BusinessLogEventType.ACTION),
                "user-1", Set.of("organization-1"), "sales.contract", "submit", null, null, 20);

        assertThat(legacy.operatorOrganizationId()).isNull();
        assertThat(legacyQuery.eventTypes()).isNull();
        assertThat(legacyQuery.operatorId()).isNull();
        assertThat(legacyQuery.operatorOrganizationIds()).isNull();
        assertThat(restrictedQuery.operatorOrganizationIds()).containsExactly("organization-1");
    }

    @Test
    void shouldRedactCredentialsAndKeepTruncationVisible() {
        LogText redacted = LogText.of("password=bad token: abc Bearer eyJhbGciOiJIUzI1NiJ9");
        LogText truncated = LogText.of("x".repeat(LogText.MAX_LENGTH + 1));

        assertThat(redacted.value()).doesNotContain("bad", "abc", "eyJhbGciOiJIUzI1NiJ9")
                .contains("[REDACTED]");
        assertThat(redacted.redacted()).isTrue();
        assertThat(truncated.value()).hasSize(LogText.MAX_LENGTH);
        assertThat(truncated.truncated()).isTrue();
    }

    @Test
    void shouldRedactQuotedAndNestedJsonCredentialsAndWholeCredentialHeaders() {
        String raw = "{\"password\":\"p@ss\",\"nested\":{\"token\":'nested-token'},"
                + "'cookie':'session-cookie',Authorization: \"Bearer bearer-token\",Cookie: session=header-cookie; theme=dark}";

        LogText redacted = LogText.of(raw);

        assertThat(redacted.value()).doesNotContain("p@ss", "nested-token", "session-cookie", "bearer-token", "header-cookie")
                .contains("[REDACTED]");
        assertThat(redacted.redacted()).isTrue();
    }

    @Test
    void shouldKeepActionMetricsUnknownWhenNoExecutionSourceProvidesThem() {
        ActionLogDetails details = new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS,
                "SERVICE", null, null, null, null);

        assertThat(details.durationMillis()).isNull();
        assertThat(details.affectedRecordCount()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new ActionLogDetails(
                ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", -1L, null, null, null));
    }

    @Test
    void shouldRejectInvalidCursorAndQueryBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BusinessLogContext(
                "event-1", Instant.now(), Instant.now(), "trace id", null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BusinessLogQuery(
                Instant.parse("2026-09-12T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z"),
                null, null, null, null, null, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessLogQuery.newest(201));
    }
}
