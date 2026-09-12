package net.ximatai.muyun.spring.ability.logging;

import net.ximatai.muyun.spring.ability.query.QueryCondition;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessLogQueryContractTest {
    @Test
    void shouldExposeOnlyBusinessRelevantConditionsForEachLogStream() {
        assertThat(BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.LOGIN_AUDIT).schema().fields())
                .extracting(field -> field.name()).containsExactly("occurredAt", "operatorId", "loginOutcome");
        assertThat(BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.BUSINESS_ACTIVITY).schema().fields())
                .extracting(field -> field.name()).containsExactly("occurredAt", "operatorId", "moduleAlias", "actionCode");
        assertThat(BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.REQUEST_ERROR).schema().fields())
                .extracting(field -> field.name()).containsExactly("occurredAt", "operatorId", "moduleAlias", "actionCode",
                        "errorCode", "httpStatus");
        assertThat(BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.REQUEST_ERROR).schema().defaultSorts())
                .singleElement().satisfies(sort -> {
                    assertThat(sort.field()).isEqualTo("occurredAt");
                    assertThat(sort.desc()).isTrue();
                });
    }

    @Test
    void shouldTranslateStandardLoginConditionsWithoutExposingCursor() {
        BusinessLogQueryContract contract = BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.LOGIN_AUDIT);
        Instant from = Instant.parse("2026-09-12T00:00:00Z");
        Instant to = Instant.parse("2026-09-12T01:00:00Z");

        BusinessLogQuery query = contract.toQuery(request(List.of(
                condition("occurredAt", QueryOperator.BETWEEN, from, to),
                condition("operatorId", QueryOperator.EQ, "user-1"),
                condition("loginOutcome", QueryOperator.EQ, "SUCCESS")
        ), List.of(new QuerySort("occurredAt", true))), 200);

        assertThat(query.occurredFrom()).isEqualTo(from);
        assertThat(query.occurredTo()).isEqualTo(to);
        assertThat(query.operatorId()).isEqualTo("user-1");
        assertThat(query.loginOutcome()).isEqualTo(LoginLogDetails.LoginOutcome.SUCCESS);
        assertThat(query.httpStatus()).isNull();
        assertThat(query.cursor()).isNull();
        assertThat(query.limit()).isEqualTo(200);
    }

    @Test
    void shouldTranslateRequestErrorStatusAndRejectFieldsOutsideTheProfile() {
        BusinessLogQueryContract errors = BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.REQUEST_ERROR);
        BusinessLogQuery query = errors.toQuery(request(List.of(
                condition("errorCode", QueryOperator.EQ, "ORDER_CONFLICT"),
                condition("httpStatus", QueryOperator.EQ, 409)
        ), List.of()), 200);

        assertThat(query.errorCode()).isEqualTo("ORDER_CONFLICT");
        assertThat(query.httpStatus()).isEqualTo(409);
        assertThatIllegalArgumentException().isThrownBy(() -> errors.toQuery(request(List.of(
                condition("loginOutcome", QueryOperator.EQ, "FAILURE")
        ), List.of()), 200)).withMessageContaining("query field is not supported");
    }

    @Test
    void shouldReuseBusinessActivityConditionsForBoundedStatistics() {
        BusinessLogQueryContract contract = BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.BUSINESS_ACTIVITY);
        BusinessLogStatisticsQuery statistics = contract.toStatisticsQuery(request(List.of(
                condition("operatorId", QueryOperator.EQ, "user-1"),
                condition("moduleAlias", QueryOperator.EQ, "sales.order"),
                condition("actionCode", QueryOperator.EQ, "submit")
        ), List.of()), 5_000);

        assertThat(statistics.operatorId()).isEqualTo("user-1");
        assertThat(statistics.moduleAlias()).isEqualTo("sales.order");
        assertThat(statistics.actionCode()).isEqualTo("submit");
        assertThat(statistics.maximumEvents()).isEqualTo(5_000);
    }

    @Test
    void shouldRejectGenericDslAndAnySortOtherThanStableOccurrenceOrder() {
        BusinessLogQueryContract contract = BusinessLogQueryContract.forProfile(BusinessLogQueryProfile.BUSINESS_ACTIVITY);
        assertThatIllegalArgumentException().isThrownBy(() -> contract.toQuery(request(List.of(),
                List.of(new QuerySort("moduleAlias", false))), 200))
                .withMessageContaining("occurredAt descending");
        QueryRequest quickSearch = new QueryRequest(List.of(), null, Map.of(), List.of(), null, null,
                Map.of(), "sales", List.of("moduleAlias"), false, null);
        assertThatIllegalArgumentException().isThrownBy(() -> contract.toQuery(quickSearch, 200))
                .withMessageContaining("flat conditions");
    }

    private static QueryRequest request(List<QueryCondition> conditions, List<QuerySort> sorts) {
        return new QueryRequest(conditions, null, Map.of(), sorts, null, null, Map.of(), null, List.of(), false, null);
    }

    private static QueryCondition condition(String field, QueryOperator operator, Object... values) {
        return new QueryCondition(field, operator, List.of(values), null);
    }
}
