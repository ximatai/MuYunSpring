package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageResult;
import net.ximatai.muyun.spring.ability.logging.LogText;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogEventResponseTest {
    @Test
    void shouldProjectLoginOutcomeForTheStandardListColumn() {
        LoginLogEvent event = new LoginLogEvent(new BusinessLogContext("event", Instant.EPOCH, Instant.EPOCH,
                "trace", null, "operator", null, "iam.login", "login"),
                new LoginLogDetails("PASSWORD", LoginLogDetails.LoginOutcome.SUCCESS, null, null, null, null));

        assertThat(BusinessLogEventResponse.from(event).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldExcludeInternalExceptionDiagnosticsFromStandardRequestErrorResponse() {
        RequestErrorLogEvent event = new RequestErrorLogEvent(new BusinessLogContext("event", Instant.EPOCH,
                Instant.EPOCH, "trace", "tenant", "operator", "organization", "platform.test", "query"),
                new RequestErrorLogDetails("GET", "/internal", "platform.test.query", 12, 500, "ERROR",
                        LogText.of("request failed"), "IllegalStateException", LogText.of("internal message"),
                        LogText.of("internal stack"), "CONTROLLER", true));

        BusinessLogEventResponse response = BusinessLogEventResponse.from(event);

        assertThat(response.details()).isInstanceOf(BusinessLogEventResponse.RequestErrorLogSafeDetails.class);
        var details = (BusinessLogEventResponse.RequestErrorLogSafeDetails) response.details();
        assertThat(details.method()).isEqualTo("GET");
        assertThat(details.path()).isEqualTo("/internal");
        assertThat(details.responseSummary().value()).isEqualTo("request failed");
        assertThat(details.getClass().getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("exceptionType", "exceptionMessage", "stackTrace");
    }

    @Test
    void shouldResolveOnePageOfOperatorIdentitiesInOneBatch() {
        ActionLogEvent first = action("event-1", "user-1", "organization-history");
        ActionLogEvent second = action("event-2", "user-2", "organization-2");
        BusinessLogOperatorIdentityLookup lookup = mock(BusinessLogOperatorIdentityLookup.class);
        BusinessLogOperatorIdentityKey firstKey = new BusinessLogOperatorIdentityKey("tenant", "user-1", "organization-history");
        BusinessLogOperatorIdentityKey secondKey = new BusinessLogOperatorIdentityKey("tenant", "user-2", "organization-2");
        when(lookup.resolve(any())).thenReturn(Map.of(
                firstKey, new BusinessLogOperatorIdentity("Alice Employee", "alice", "organization-history", "历史机构",
                        "department-1", "当前部门"),
                secondKey, new BusinessLogOperatorIdentity(null, "bob", "organization-2", "机构二", null, null)));

        var response = BusinessLogWebPageResponses.from(
                new BusinessLogPageResult(List.of(first, second), 2, 1, 20, true), lookup);

        assertThat(response.records()).extracting(event -> event.operatorIdentity().organizationName())
                .containsExactly("历史机构", "机构二");
        assertThat(response.records().getFirst().operatorIdentity().departmentName()).isEqualTo("当前部门");
        verify(lookup, times(1)).resolve(List.of(firstKey, secondKey));
    }

    @Test
    void shouldResolvePlatformOperatorIdentityWithoutTenant() {
        LoginLogEvent event = new LoginLogEvent(new BusinessLogContext("event", Instant.EPOCH, Instant.EPOCH,
                "trace", null, "platform.user.super_admin", null, "iam.login", "login"),
                new LoginLogDetails("PASSWORD", LoginLogDetails.LoginOutcome.SUCCESS, null, null, "admin", "admin"));
        BusinessLogOperatorIdentityKey key = new BusinessLogOperatorIdentityKey(null, "platform.user.super_admin", null);
        BusinessLogOperatorIdentityLookup lookup = mock(BusinessLogOperatorIdentityLookup.class);
        when(lookup.resolve(List.of(key))).thenReturn(Map.of(key,
                new BusinessLogOperatorIdentity(null, "admin", null, null, null, null)));

        BusinessLogEventResponse response = BusinessLogEventResponse.from(event, lookup);

        assertThat(response.operatorIdentity().username()).isEqualTo("admin");
    }

    private static ActionLogEvent action(String eventId, String userId, String organizationId) {
        return new ActionLogEvent(new BusinessLogContext(eventId, Instant.EPOCH, Instant.EPOCH, "trace", "tenant",
                userId, organizationId, "platform.test", "query"),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "TEST", null, null, null, null));
    }
}
