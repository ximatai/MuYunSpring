package net.ximatai.muyun.spring.platform.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogCursor;
import net.ximatai.muyun.spring.ability.logging.BusinessLogDetails;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStorageException;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL append-only store for the first business logging phase.
 *
 * <p>The adapter owns a small, independent {@code muyun_log} schema and intentionally has no
 * entity DAO, mutation API or web endpoint. Failures are propagated as
 * {@link BusinessLogStorageException}; policy decisions remain with the collector.</p>
 */
public class PostgresBusinessLogStore implements BusinessLogStore, PlatformBootstrapTask {
    private static final String INSERT = """
            insert into muyun_log.business_log_event
                (event_id, event_type, occurred_at, captured_at, trace_id, tenant_id, operator_id,
                 operator_organization_id, module_alias, action_code, error_code, login_outcome, login_account, http_status, details_json)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (event_id) do nothing
            """;
    private static final String EVENT_COLUMNS = "event_id, event_type, occurred_at, captured_at, trace_id, "
            + "tenant_id, operator_id, operator_organization_id, module_alias, action_code, details_json";

    private final SqlConnectionExecutor connections;
    private final ObjectMapper objectMapper;

    public PostgresBusinessLogStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper().findAndRegisterModules());
    }

    public PostgresBusinessLogStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.connections = dataSourceExecutor(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Reuses the platform's Jdbi-owned connection lifecycle when no Spring DataSource is exposed. */
    public PostgresBusinessLogStore(Jdbi jdbi) {
        this(jdbi, new ObjectMapper().findAndRegisterModules());
    }

    public PostgresBusinessLogStore(Jdbi jdbi, ObjectMapper objectMapper) {
        this.connections = jdbiExecutor(Objects.requireNonNull(jdbi, "jdbi must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void run() {
        try {
            connections.withConnection(connection -> {
                PostgresBusinessLogSchemaInitializer.ensure(connection);
                return null;
            });
        } catch (SQLException exception) {
            throw storageFailure("initialize business log schema", exception);
        }
    }

    @Override
    public String name() {
        return "platform.business-log-schema";
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public BusinessLogWriteResult append(BusinessLogEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    return append(statement, event);
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("append business log event", exception);
        }
    }

    @Override
    public List<BusinessLogWriteResult> appendAll(Collection<? extends BusinessLogEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            return List.of();
        }
        try {
            return connections.withConnection(connection -> {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    List<BusinessLogWriteResult> results = new ArrayList<>(events.size());
                    for (BusinessLogEvent event : events) {
                        results.add(append(statement, Objects.requireNonNull(event, "events must not contain null")));
                    }
                    connection.commit();
                    return List.copyOf(results);
                } catch (RuntimeException | SQLException exception) {
                    rollback(connection, exception);
                    if (exception instanceof BusinessLogStorageException storageException) {
                        throw storageException;
                    }
                    if (exception instanceof SQLException sqlException) {
                        throw storageFailure("append business log event batch", sqlException);
                    }
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("append business log event batch", exception);
        }
    }

    @Override
    public BusinessLogReadPage read(BusinessLogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder sql = new StringBuilder("select ").append(EVENT_COLUMNS)
                .append(" from muyun_log.business_log_event where 1=1");
        List<Object> parameters = new ArrayList<>();
        appendFilter(sql, parameters, "occurred_at >= ?", query.occurredFrom());
        appendFilter(sql, parameters, "occurred_at <= ?", query.occurredTo());
        appendFilter(sql, parameters, "tenant_id = ?", query.tenantId());
        appendInFilter(sql, parameters, "event_type", query.eventTypes() == null ? null
                : query.eventTypes().stream().map(Enum::name).toList());
        appendFilter(sql, parameters, "operator_id = ?", query.operatorId());
        appendInFilter(sql, parameters, "operator_organization_id", query.operatorOrganizationIds());
        appendFilter(sql, parameters, "module_alias = ?", query.moduleAlias());
        appendFilter(sql, parameters, "action_code = ?", query.actionCode());
        appendFilter(sql, parameters, "error_code = ?", query.errorCode());
        appendFilter(sql, parameters, "login_outcome = ?", query.loginOutcome() == null ? null
                : query.loginOutcome().name());
        appendFilter(sql, parameters, "login_account = ?", query.loginAccount());
        appendFilter(sql, parameters, "http_status = ?", query.httpStatus());
        if (query.cursor() != null) {
            sql.append(" and (occurred_at, event_id) < (?, ?)");
            parameters.add(query.cursor().occurredAt());
            parameters.add(query.cursor().eventId());
        }
        sql.append(" order by occurred_at desc, event_id desc limit ?");
        parameters.add(query.limit() + 1);
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    bind(statement, parameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<BusinessLogEvent> events = new ArrayList<>();
                        while (resultSet.next()) {
                            events.add(readEvent(resultSet));
                        }
                        boolean hasMore = events.size() > query.limit();
                        if (hasMore) {
                            events.removeLast();
                        }
                        BusinessLogCursor nextCursor = hasMore ? cursorFor(events.getLast()) : null;
                        return new BusinessLogReadPage(events, nextCursor);
                    }
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("read business log events", exception);
        }
    }

    @Override
    public Optional<BusinessLogEvent> findById(String eventId) {
        String normalized = eventId == null ? null : eventId.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("eventId must be between 1 and 128 characters");
        }
        String sql = "select " + EVENT_COLUMNS + " from muyun_log.business_log_event where event_id = ?";
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, normalized);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? Optional.of(readEvent(resultSet)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("read business log event", exception);
        }
    }

    private BusinessLogWriteResult append(PreparedStatement statement, BusinessLogEvent event) throws SQLException {
        BusinessLogContext context = event.context();
        statement.setString(1, context.eventId());
        statement.setString(2, event.eventType().name());
        statement.setObject(3, OffsetDateTime.ofInstant(context.occurredAt(), ZoneOffset.UTC));
        statement.setObject(4, OffsetDateTime.ofInstant(context.capturedAt(), ZoneOffset.UTC));
        statement.setString(5, context.traceId());
        statement.setString(6, context.tenantId());
        statement.setString(7, context.operatorId());
        statement.setString(8, context.operatorOrganizationId());
        statement.setString(9, context.moduleAlias());
        statement.setString(10, context.actionCode());
        statement.setString(11, errorCode(event));
        statement.setString(12, loginOutcome(event));
        statement.setString(13, loginAccount(event));
        if (httpStatus(event) == null) {
            statement.setNull(14, java.sql.Types.INTEGER);
        } else {
            statement.setInt(14, httpStatus(event));
        }
        statement.setString(15, serialize(event.details()));
        int updated = statement.executeUpdate();
        return new BusinessLogWriteResult(context.eventId(), updated == 1
                ? BusinessLogWriteResult.Status.APPENDED : BusinessLogWriteResult.Status.DUPLICATE_IGNORED);
    }

    private String serialize(BusinessLogDetails details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new BusinessLogStorageException("Could not serialize business log details", exception);
        }
    }

    private BusinessLogEvent readEvent(ResultSet resultSet) throws SQLException {
        BusinessLogEventType type = BusinessLogEventType.valueOf(resultSet.getString("event_type"));
        BusinessLogContext context = new BusinessLogContext(
                resultSet.getString("event_id"),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "captured_at"),
                resultSet.getString("trace_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("operator_id"),
                resultSet.getString("operator_organization_id"),
                resultSet.getString("module_alias"),
                resultSet.getString("action_code"));
        String detailsJson = resultSet.getString("details_json");
        return switch (type) {
            case LOGIN -> new LoginLogEvent(context, deserialize(detailsJson, LoginLogDetails.class));
            case ACTION -> new ActionLogEvent(context, deserialize(detailsJson, ActionLogDetails.class));
            case REQUEST_ERROR -> new RequestErrorLogEvent(context, deserialize(detailsJson, RequestErrorLogDetails.class));
            case PAGE_ACCESS -> new PageAccessLogEvent(context, deserialize(detailsJson, PageAccessLogDetails.class));
        };
    }

    private <T extends BusinessLogDetails> T deserialize(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessLogStorageException("Could not deserialize stored business log details", exception);
        }
    }

    private static void appendFilter(StringBuilder sql, List<Object> parameters, String condition, Object value) {
        if (value != null) {
            sql.append(" and ").append(condition);
            parameters.add(value);
        }
    }

    private static void appendInFilter(StringBuilder sql, List<Object> parameters, String column,
                                       Collection<String> values) {
        if (values == null) {
            return;
        }
        if (values.isEmpty()) {
            sql.append(" and 1=0");
            return;
        }
        sql.append(" and ").append(column).append(" in (");
        sql.append("?, ".repeat(values.size()).substring(0, values.size() * 3 - 2));
        sql.append(')');
        parameters.addAll(values);
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof Instant instant) {
                statement.setObject(index + 1, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String errorCode(BusinessLogEvent event) {
        return event instanceof RequestErrorLogEvent requestError ? requestError.details().errorCode() : null;
    }

    private static String loginOutcome(BusinessLogEvent event) {
        return event instanceof LoginLogEvent login ? login.details().outcome().name() : null;
    }

    /** Uses the confirmed account when authentication identified one, otherwise the submitted account. */
    private static String loginAccount(BusinessLogEvent event) {
        if (!(event instanceof LoginLogEvent login)) {
            return null;
        }
        return login.details().confirmedAccount() != null
                ? login.details().confirmedAccount()
                : login.details().claimedAccount();
    }

    private static Integer httpStatus(BusinessLogEvent event) {
        return event instanceof RequestErrorLogEvent requestError ? requestError.details().httpStatus() : null;
    }

    private static BusinessLogCursor cursorFor(BusinessLogEvent event) {
        return new BusinessLogCursor(event.context().occurredAt(), event.eventId());
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static BusinessLogStorageException storageFailure(String action, SQLException exception) {
        return new BusinessLogStorageException("Could not " + action, exception);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private interface SqlConnectionExecutor {
        <T> T withConnection(SqlWork<T> work) throws SQLException;
    }

    private static SqlConnectionExecutor dataSourceExecutor(DataSource dataSource) {
        return new SqlConnectionExecutor() {
            @Override
            public <T> T withConnection(SqlWork<T> work) throws SQLException {
                try (Connection connection = dataSource.getConnection()) {
                    return work.execute(connection);
                }
            }
        };
    }

    private static SqlConnectionExecutor jdbiExecutor(Jdbi jdbi) {
        return new SqlConnectionExecutor() {
            @Override
            public <T> T withConnection(SqlWork<T> work) throws SQLException {
                return jdbi.withHandle(handle -> work.execute(handle.getConnection()));
            }
        };
    }
}
