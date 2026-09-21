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
        assertThat(legacy.operatorAccount()).isNull();
        assertThat(legacyQuery.eventTypes()).isNull();
        assertThat(legacyQuery.operatorId()).isNull();
        assertThat(legacyQuery.operatorOrganizationIds()).isNull();
        assertThat(restrictedQuery.operatorOrganizationIds()).containsExactly("organization-1");
    }

    @Test
    void shouldRetainOperatorAccountAsAnOptionalEventTimeSnapshot() {
        BusinessLogContext context = new BusinessLogContext("event-1", Instant.now(), Instant.now(), null,
                "tenant-1", "user-1", "alice", "organization-1", "sales.contract", "submit");

        assertThat(context.operatorAccount()).isEqualTo("alice");
        assertThat(context.operatorOrganizationId()).isEqualTo("organization-1");
    }

    @Test
    void shouldRetainDepartmentAsAnEventTimeSnapshotInsteadOfConsultingLaterIamState() {
        BusinessLogContext context = new BusinessLogContext("event-1", Instant.now(), Instant.now(), null,
                "tenant-1", "user-1", "alice", "organization-1", "department-at-event",
                "sales.contract", "submit");

        assertThat(context.operatorDepartmentId()).isEqualTo("department-at-event");
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

    @Test
    void shouldKeepRetentionStorageWorkExplicitlyBounded() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(new BusinessLogRetentionRequest(cutoff, Set.of(BusinessLogEventType.LOGIN), 1_000, 20))
                .satisfies(request -> {
                    assertThat(request.occurredBefore()).isEqualTo(cutoff);
                    assertThat(request.eventTypes()).containsExactly(BusinessLogEventType.LOGIN);
                });
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BusinessLogRetentionRequest(cutoff, 10_001, 20));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BusinessLogRetentionRequest(cutoff, 1_000, 1_001));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new BusinessLogRetentionResult(cutoff, 1, 0,
                        BusinessLogRetentionResult.Status.ALREADY_RUNNING));
    }

    @Test
    void shouldModelRetentionPeriodAsAnEventTypeBusinessPolicy() {
        BusinessLogRetentionPolicy policy = new BusinessLogRetentionPolicy(BusinessLogEventType.ACTION,
                true, 90, Instant.parse("2026-09-21T00:00:00Z"), "admin");

        assertThat(policy.automaticCleanupEnabled()).isTrue();
        assertThat(policy.retentionDays()).isEqualTo(90);
        assertThat(BusinessLogRetentionPolicy.defaultDisabled(BusinessLogEventType.PAGE_ACCESS))
                .satisfies(defaultPolicy -> {
                    assertThat(defaultPolicy.automaticCleanupEnabled()).isFalse();
                    assertThat(defaultPolicy.retentionDays()).isEqualTo(180);
                });
        assertThatIllegalArgumentException().isThrownBy(() -> new BusinessLogRetentionPolicy(
                BusinessLogEventType.ACTION, true, 0, null, null));
    }
}
