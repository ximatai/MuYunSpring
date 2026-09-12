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
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;

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

/**
 * PostgreSQL append-only store for the first business logging phase.
 *
 * <p>The adapter owns a small, independent {@code muyun_log} schema and intentionally has no
 * entity DAO, mutation API or web endpoint. Failures are propagated as
 * {@link BusinessLogStorageException}; policy decisions remain with the collector.</p>
 */
public class PostgresBusinessLogStore implements BusinessLogStore {
    private static final String INSERT = """
            insert into muyun_log.business_log_event
                (event_id, event_type, occurred_at, captured_at, trace_id, tenant_id, operator_id,
                 module_alias, action_code, error_code, details_json)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (event_id) do nothing
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PostgresBusinessLogStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper().findAndRegisterModules());
    }

    public PostgresBusinessLogStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public BusinessLogWriteResult append(BusinessLogEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            return append(statement, event);
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
        try (Connection connection = dataSource.getConnection()) {
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
        } catch (SQLException exception) {
            throw storageFailure("append business log event batch", exception);
        }
    }

    @Override
    public BusinessLogReadPage read(BusinessLogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder sql = new StringBuilder("select event_id, event_type, occurred_at, captured_at, trace_id, "
                + "tenant_id, operator_id, module_alias, action_code, details_json from muyun_log.business_log_event where 1=1");
        List<Object> parameters = new ArrayList<>();
        appendFilter(sql, parameters, "occurred_at >= ?", query.occurredFrom());
        appendFilter(sql, parameters, "occurred_at <= ?", query.occurredTo());
        appendFilter(sql, parameters, "tenant_id = ?", query.tenantId());
        appendFilter(sql, parameters, "module_alias = ?", query.moduleAlias());
        appendFilter(sql, parameters, "action_code = ?", query.actionCode());
        appendFilter(sql, parameters, "error_code = ?", query.errorCode());
        if (query.cursor() != null) {
            sql.append(" and (occurred_at, event_id) < (?, ?)");
            parameters.add(query.cursor().occurredAt());
            parameters.add(query.cursor().eventId());
        }
        sql.append(" order by occurred_at desc, event_id desc limit ?");
        parameters.add(query.limit() + 1);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
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
        } catch (SQLException exception) {
            throw storageFailure("read business log events", exception);
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
        statement.setString(8, context.moduleAlias());
        statement.setString(9, context.actionCode());
        statement.setString(10, errorCode(event));
        statement.setString(11, serialize(event.details()));
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
}
