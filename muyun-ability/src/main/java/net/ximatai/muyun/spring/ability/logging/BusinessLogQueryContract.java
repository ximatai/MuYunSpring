package net.ximatai.muyun.spring.ability.logging;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryCondition;
import net.ximatai.muyun.spring.ability.query.QueryCriteria;
import net.ximatai.muyun.spring.ability.query.QueryCriteriaComposition;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryReference;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Type-safe adapter between the platform-standard query request and one business-log stream.
 *
 * <p>The log store remains cursor based, while controllers use this contract for its schema,
 * condition vocabulary and fixed sort. It accepts the standard root-AND criteria form so persistent
 * list controls use the same request shape as ordinary record lists.</p>
 */
public final class BusinessLogQueryContract {
    public static final String OCCURRED_AT = "occurredAt";
    public static final String OPERATOR_ID = "operatorId";
    public static final String MODULE_ALIAS = "moduleAlias";
    public static final String ACTION_CODE = "actionCode";
    public static final String LOGIN_ACCOUNT = "loginAccount";
    public static final String LOGIN_OUTCOME = "loginOutcome";
    public static final String ERROR_CODE = "errorCode";
    public static final String HTTP_STATUS = "httpStatus";

    private final BusinessLogQueryProfile profile;
    private final QueryDescriptor descriptor;
    private final QuerySchema schema;

    private BusinessLogQueryContract(BusinessLogQueryProfile profile, QueryDescriptor descriptor) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.schema = QuerySchema.from(descriptor).withCriteriaComposition(QueryCriteriaComposition.FLAT_AND);
    }

    public static BusinessLogQueryContract forProfile(BusinessLogQueryProfile profile) {
        return new BusinessLogQueryContract(profile, descriptorFor(Objects.requireNonNull(profile, "profile must not be null")));
    }

    public QuerySchema schema() {
        return schema;
    }

    /** Converts only the declared profile conditions to storage-neutral log filters. */
    public BusinessLogQuery toQuery(QueryRequest request, int storagePageSize) {
        requireStoragePageSize(storagePageSize);
        QueryRequest normalized = request == null ? QueryRequest.empty() : request;
        rejectUnsupportedSurfaces(normalized);
        validateFixedSort(normalized);

        Instant occurredFrom = null;
        Instant occurredTo = null;
        String operatorId = null;
        String moduleAlias = null;
        String actionCode = null;
        String errorCode = null;
        String loginAccount = null;
        LoginLogDetails.LoginOutcome loginOutcome = null;
        Integer httpStatus = null;
        Map<String, Boolean> supplied = new LinkedHashMap<>();

        for (var condition : conditions(normalized)) {
            QueryField field = requireField(condition.fieldName());
            QueryOperator operator = condition.operator() == null ? field.defaultOperator() : condition.operator();
            if (!field.operators().contains(operator)) {
                throw unsupportedOperator(field.fieldName(), operator);
            }
            if (supplied.put(field.fieldName(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("query field may only be supplied once: " + field.fieldName());
            }
            List<Object> values = normalizedValues(field, operator, condition.values());
            switch (field.fieldName()) {
                case OCCURRED_AT -> {
                    if (operator == QueryOperator.BETWEEN) {
                        occurredFrom = instant(field.fieldName(), values.get(0));
                        occurredTo = instant(field.fieldName(), values.get(1));
                    } else if (operator == QueryOperator.GTE) {
                        occurredFrom = instant(field.fieldName(), values.getFirst());
                    } else if (operator == QueryOperator.LTE) {
                        occurredTo = instant(field.fieldName(), values.getFirst());
                    } else {
                        throw unsupportedOperator(field.fieldName(), operator);
                    }
                }
                case OPERATOR_ID -> operatorId = text(field.fieldName(), values.getFirst());
                case MODULE_ALIAS -> moduleAlias = text(field.fieldName(), values.getFirst());
                case ACTION_CODE -> actionCode = text(field.fieldName(), values.getFirst());
                case LOGIN_ACCOUNT -> loginAccount = text(field.fieldName(), values.getFirst());
                case ERROR_CODE -> errorCode = text(field.fieldName(), values.getFirst());
                case LOGIN_OUTCOME -> loginOutcome = loginOutcome(values.getFirst());
                case HTTP_STATUS -> httpStatus = integer(field.fieldName(), values.getFirst());
                default -> throw new IllegalArgumentException("query field is not supported by " + profile + ": "
                        + field.fieldName());
            }
        }
        return new BusinessLogQuery(occurredFrom, occurredTo, null, null, operatorId, null,
                moduleAlias, actionCode, errorCode, loginAccount, loginOutcome, httpStatus, null, storagePageSize);
    }

    /** Reuses the activity list conditions for bounded action and page-access aggregations. */
    public BusinessLogStatisticsQuery toStatisticsQuery(QueryRequest request, int maximumEvents) {
        if (profile != BusinessLogQueryProfile.BUSINESS_ACTIVITY) {
            throw new IllegalStateException("statistics query is only supported by business activity logs");
        }
        BusinessLogQuery query = toQuery(request, 200);
        return new BusinessLogStatisticsQuery(query.occurredFrom(), query.occurredTo(), query.tenantId(),
                query.operatorId(), query.operatorOrganizationIds(), query.moduleAlias(), query.actionCode(),
                maximumEvents);
    }

    private QueryField requireField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("query field must not be blank");
        }
        QueryField field = descriptor.field(fieldName.trim());
        if (field == null) {
            throw new IllegalArgumentException("query field is not supported by " + descriptor.scopeName() + ": "
                    + fieldName.trim());
        }
        return field;
    }

    private void rejectUnsupportedSurfaces(QueryRequest request) {
        if (!request.queryForm().isEmpty() || request.uiConfigId() != null || request.queryTemplateId() != null
                || !request.externalQueryValues().isEmpty() || request.navigationSession()
                || request.navigationQueryKey() != null || request.quickSearch() != null
                || !request.quickSearchFields().isEmpty()) {
            throw new IllegalArgumentException("only standard field conditions are supported by " + descriptor.scopeName());
        }
    }

    private static List<QueryCondition> conditions(QueryRequest request) {
        QueryCriteria.validateConditions(request.conditions());
        if (request.criteria() == null || request.criteria().isEmpty()) {
            return request.conditions();
        }
        QueryCriteria.validate(request.criteria(), QueryCriteriaComposition.FLAT_AND);
        if (!request.conditions().isEmpty()) {
            throw new IllegalArgumentException("conditions and criteria cannot be supplied together");
        }
        return request.criteria().children().stream().map(node -> (QueryCondition) node).toList();
    }

    private void validateFixedSort(QueryRequest request) {
        if (request.sorts().isEmpty()) {
            return;
        }
        if (request.sorts().size() != 1 || !OCCURRED_AT.equals(request.sorts().getFirst().field())
                || !request.sorts().getFirst().desc()) {
            throw new IllegalArgumentException("business-log queries are fixed to occurredAt descending");
        }
    }

    private static List<Object> normalizedValues(QueryField field, QueryOperator operator, List<Object> rawValues) {
        List<Object> values = rawValues == null ? List.of() : rawValues.stream()
                .filter(value -> value != null && (!(value instanceof String text) || !text.isBlank()))
                .map(field.valueType()::normalize)
                .toList();
        int expected = operator == QueryOperator.BETWEEN ? 2 : 1;
        if (values.size() != expected) {
            throw new IllegalArgumentException("query operator requires exactly " + expected + " value"
                    + (expected == 1 ? "" : "s") + ": " + field.fieldName() + "." + operator);
        }
        return values;
    }

    private static String text(String fieldName, Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("invalid query value type: " + fieldName);
        }
        return text;
    }

    private static Instant instant(String fieldName, Object value) {
        if (!(value instanceof Instant instant)) {
            throw new IllegalArgumentException("invalid query value type: " + fieldName);
        }
        return instant;
    }

    private static Integer integer(String fieldName, Object value) {
        if (!(value instanceof Integer integer)) {
            throw new IllegalArgumentException("invalid query value type: " + fieldName);
        }
        return integer;
    }

    private static LoginLogDetails.LoginOutcome loginOutcome(Object value) {
        try {
            return LoginLogDetails.LoginOutcome.valueOf(text(LOGIN_OUTCOME, value).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("loginOutcome must be SUCCESS or FAILURE", exception);
        }
    }

    private static IllegalArgumentException unsupportedOperator(String fieldName, QueryOperator operator) {
        return new IllegalArgumentException("query operator is not supported: " + fieldName + "." + operator);
    }

    private static void requireStoragePageSize(int storagePageSize) {
        if (storagePageSize < 1 || storagePageSize > 200) {
            throw new IllegalArgumentException("storagePageSize must be between 1 and 200");
        }
    }

    private static QueryDescriptor descriptorFor(BusinessLogQueryProfile profile) {
        QueryDescriptor.Builder builder = QueryDescriptor.builder(scopeName(profile))
                .field(occurredAtField())
                .field(operatorField());
        switch (profile) {
            case LOGIN_AUDIT -> builder
                    .field(textField(LOGIN_ACCOUNT, "登录账号"))
                    .field(enumField(LOGIN_OUTCOME, "登录结果"));
            case BUSINESS_ACTIVITY -> builder
                    .field(textField(MODULE_ALIAS, "业务模块"))
                    .field(textField(ACTION_CODE, "动作"));
            case REQUEST_ERROR -> builder
                    .field(textField(MODULE_ALIAS, "业务模块"))
                    .field(textField(ACTION_CODE, "动作"))
                    .field(textField(ERROR_CODE, "错误码"))
                    .field(integerField(HTTP_STATUS, "HTTP 状态"));
        }
        return builder.defaultSort(Sort.desc(OCCURRED_AT)).build();
    }

    private static QueryField occurredAtField() {
        return new QueryField(OCCURRED_AT, "发生时间", QueryValueType.INSTANT,
                EnumSet.of(QueryOperator.BETWEEN, QueryOperator.GTE, QueryOperator.LTE), QueryOperator.BETWEEN,
                true, false, null, null, null).withPersistentControl(QueryOperator.BETWEEN);
    }

    private static QueryField operatorField() {
        return textField(OPERATOR_ID, "操作用户")
                .withReference(new QueryReference("iam.user", ReferenceCardinality.ONE, "username"))
                .withPersistentControl(QueryOperator.EQ);
    }

    private static QueryField textField(String name, String title) {
        return new QueryField(name, title, QueryValueType.STRING, EnumSet.of(QueryOperator.EQ), QueryOperator.EQ,
                false, false, null, null, null);
    }

    private static QueryField enumField(String name, String title) {
        return textField(name, title);
    }

    private static QueryField integerField(String name, String title) {
        return new QueryField(name, title, QueryValueType.INTEGER, EnumSet.of(QueryOperator.EQ), QueryOperator.EQ,
                false, false, null, null, null);
    }

    private static String scopeName(BusinessLogQueryProfile profile) {
        return switch (profile) {
            case LOGIN_AUDIT -> "iam.login_audit_log";
            case BUSINESS_ACTIVITY -> "platform.business_activity_log";
            case REQUEST_ERROR -> "platform.request_error_log";
        };
    }
}
