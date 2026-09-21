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
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidate;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidatePage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidateQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigation;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigationItem;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyConflictException;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionStore;
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
 * PostgreSQL store for immutable business-log facts and explicit retention maintenance.
 *
 * <p>The adapter owns a small, independent {@code muyun_log} schema. It exposes append/read plus
 * the explicit bounded-retention contract, but no business entity DAO or web endpoint. Failures
 * are propagated as {@link BusinessLogStorageException}; retention policy decisions remain above
 * this adapter.</p>
 */
public class PostgresBusinessLogStore implements BusinessLogStore, BusinessLogRetentionStore,
        BusinessLogRetentionPolicyStore, PlatformBootstrapTask {
    static final long RETENTION_ADVISORY_LOCK_KEY = 0x4D_55_59_55_4E_4C_4F_47L;
    private static final String INSERT = """
            insert into muyun_log.business_log_event
                (event_id, event_type, occurred_at, captured_at, trace_id, tenant_id, operator_id,
                 operator_account, operator_organization_id, operator_department_id, module_alias, action_code,
                 error_code, login_outcome, login_account, http_status, action_outcome, entity_alias,
                 record_id, mutation_source, details_json)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (event_id) do nothing
            """;
    private static final String EVENT_COLUMNS = "event_id, event_type, occurred_at, captured_at, trace_id, "
            + "tenant_id, operator_id, operator_account, operator_organization_id, operator_department_id, "
            + "module_alias, action_code, details_json";

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
        appendQueryFilters(sql, parameters, query);
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
    public BusinessLogOperatorCandidatePage readOperatorCandidates(BusinessLogQuery query,
                                                                     BusinessLogOperatorCandidateQuery candidateQuery) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidateQuery, "candidateQuery must not be null");
        StringBuilder where = new StringBuilder(" from muyun_log.business_log_event where operator_id is not null");
        List<Object> parameters = new ArrayList<>();
        appendQueryFilters(where, parameters, query);
        appendCandidateFilters(where, parameters, candidateQuery);
        String grouped = "select distinct on (tenant_id, operator_id) tenant_id, operator_id, operator_account, "
                + "operator_organization_id, operator_department_id, "
                + "occurred_at as last_occurred_at" + where
                + " order by tenant_id asc nulls first, operator_id asc, occurred_at desc";
        long total = countCandidates(grouped, parameters);
        String pageSql = "select tenant_id, operator_id, operator_account, operator_organization_id, operator_department_id from (" + grouped + ") candidates"
                + " order by last_occurred_at desc, tenant_id asc nulls first, operator_id asc limit ? offset ?";
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(candidateQuery.page().pageSize());
        pageParameters.add(candidateQuery.page().offset());
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                    bind(statement, pageParameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<BusinessLogOperatorCandidate> candidates = new ArrayList<>();
                        while (resultSet.next()) {
                            candidates.add(new BusinessLogOperatorCandidate(resultSet.getString("tenant_id"),
                                    resultSet.getString("operator_id"), resultSet.getString("operator_account"),
                                    resultSet.getString("operator_organization_id"),
                                    resultSet.getString("operator_department_id")));
                        }
                        return new BusinessLogOperatorCandidatePage(candidates, total,
                                candidateQuery.page().pageNum(), candidateQuery.page().pageSize());
                    }
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("read business-log operator candidates", exception);
        }
    }

    @Override
    public BusinessLogOperatorNavigation readOperatorNavigation(BusinessLogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder where = new StringBuilder(" from muyun_log.business_log_event where operator_id is not null");
        List<Object> parameters = new ArrayList<>();
        appendQueryFilters(where, parameters, query);
        return new BusinessLogOperatorNavigation(
                navigation("select distinct tenant_id" + where + " and tenant_id is not null order by tenant_id", parameters, false),
                navigation("select distinct tenant_id, operator_organization_id" + where
                        + " and operator_organization_id is not null order by tenant_id, operator_organization_id", parameters, true),
                navigation("select distinct tenant_id, operator_organization_id, operator_department_id" + where
                        + " and operator_department_id is not null order by tenant_id, operator_organization_id, operator_department_id", parameters, true));
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

    @Override
    public BusinessLogRetentionResult purge(BusinessLogRetentionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return connections.withConnection(connection -> purge(connection, request));
        } catch (SQLException exception) {
            throw storageFailure("purge retained business log events", exception);
        }
    }

    @Override
    public List<BusinessLogRetentionPolicy> findRetentionPolicies() {
        String sql = """
                select event_type, automatic_cleanup_enabled, retention_days, version, updated_at, updated_by
                from muyun_log.business_log_retention_policy
                order by event_type
                """;
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    List<BusinessLogRetentionPolicy> policies = new ArrayList<>();
                    while (resultSet.next()) {
                        OffsetDateTime updatedAt = resultSet.getObject("updated_at", OffsetDateTime.class);
                        policies.add(new BusinessLogRetentionPolicy(
                                BusinessLogEventType.valueOf(resultSet.getString("event_type")),
                                resultSet.getBoolean("automatic_cleanup_enabled"),
                                resultSet.getInt("retention_days"),
                                resultSet.getLong("version"),
                                updatedAt == null ? null : updatedAt.toInstant(),
                                resultSet.getString("updated_by")));
                    }
                    return List.copyOf(policies);
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("read business-log retention policies", exception);
        }
    }

    @Override
    public BusinessLogRetentionPolicy saveRetentionPolicy(BusinessLogRetentionPolicy policy, long expectedVersion) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (expectedVersion < 0 || policy.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("saved retention policy version must follow expectedVersion");
        }
        String sql = """
                insert into muyun_log.business_log_retention_policy
                    (event_type, automatic_cleanup_enabled, retention_days, version, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?)
                on conflict (event_type) do update set
                    automatic_cleanup_enabled = excluded.automatic_cleanup_enabled,
                    retention_days = excluded.retention_days,
                    version = excluded.version,
                    updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by
                where muyun_log.business_log_retention_policy.version = ?
                """;
        try {
            connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, policy.eventType().name());
                    statement.setBoolean(2, policy.automaticCleanupEnabled());
                    statement.setInt(3, policy.retentionDays());
                    statement.setLong(4, policy.version());
                    if (policy.updatedAt() == null) {
                        statement.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        statement.setObject(5, OffsetDateTime.ofInstant(policy.updatedAt(), ZoneOffset.UTC));
                    }
                    statement.setString(6, policy.updatedBy());
                    statement.setLong(7, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new BusinessLogRetentionPolicyConflictException(policy.eventType());
                    }
                    return null;
                }
            });
            return policy;
        } catch (SQLException exception) {
            throw storageFailure("save business-log retention policy", exception);
        }
    }

    private static BusinessLogRetentionResult purge(Connection connection,
                                                     BusinessLogRetentionRequest request) throws SQLException {
        boolean locked = tryRetentionLock(connection);
        if (!locked) {
            return new BusinessLogRetentionResult(request.occurredBefore(), 0, 0,
                    BusinessLogRetentionResult.Status.ALREADY_RUNNING);
        }
        SQLException failure = null;
        try {
            long deleted = 0;
            for (int batch = 1; batch <= request.maximumBatches(); batch++) {
                int batchDeleted = deleteRetentionBatch(connection, request);
                deleted += batchDeleted;
                if (batchDeleted < request.batchSize()) {
                    return new BusinessLogRetentionResult(request.occurredBefore(), deleted, batch,
                            BusinessLogRetentionResult.Status.COMPLETE);
                }
            }
            return new BusinessLogRetentionResult(request.occurredBefore(), deleted, request.maximumBatches(),
                    hasExpiredEvent(connection, request)
                            ? BusinessLogRetentionResult.Status.BATCH_LIMIT_REACHED
                            : BusinessLogRetentionResult.Status.COMPLETE);
        } catch (SQLException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                releaseRetentionLock(connection);
            } catch (SQLException unlockFailure) {
                if (failure != null) {
                    failure.addSuppressed(unlockFailure);
                } else {
                    throw unlockFailure;
                }
            }
        }
    }

    private static boolean tryRetentionLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, RETENTION_ADVISORY_LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static int deleteRetentionBatch(Connection connection,
                                            BusinessLogRetentionRequest request) throws SQLException {
        List<String> eventTypes = request.eventTypes() == null ? List.of()
                : request.eventTypes().stream().map(Enum::name).sorted().toList();
        String typeFilter = eventTypes.isEmpty() ? "" : " and event_type in ("
                + "?, ".repeat(eventTypes.size()).substring(0, eventTypes.size() * 3 - 2) + ")";
        String sql = """
                delete from muyun_log.business_log_event
                where event_id in (
                    select event_id
                    from muyun_log.business_log_event
                    where occurred_at < ?%s
                    order by occurred_at, event_id
                    limit ?
                )
                """.formatted(typeFilter);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setObject(parameter++, OffsetDateTime.ofInstant(request.occurredBefore(), ZoneOffset.UTC));
            for (String eventType : eventTypes) {
                statement.setString(parameter++, eventType);
            }
            statement.setInt(parameter, request.batchSize());
            return statement.executeUpdate();
        }
    }

    private static boolean hasExpiredEvent(Connection connection,
                                           BusinessLogRetentionRequest request) throws SQLException {
        List<String> eventTypes = request.eventTypes() == null ? List.of()
                : request.eventTypes().stream().map(Enum::name).sorted().toList();
        String typeFilter = eventTypes.isEmpty() ? "" : " and event_type in ("
                + "?, ".repeat(eventTypes.size()).substring(0, eventTypes.size() * 3 - 2) + ")";
        String sql = "select 1 from muyun_log.business_log_event where occurred_at < ?"
                + typeFilter + " limit 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setObject(parameter++, OffsetDateTime.ofInstant(request.occurredBefore(), ZoneOffset.UTC));
            for (String eventType : eventTypes) {
                statement.setString(parameter++, eventType);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void releaseRetentionLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, RETENTION_ADVISORY_LOCK_KEY);
            statement.executeQuery().close();
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
        statement.setString(8, context.operatorAccount());
        statement.setString(9, context.operatorOrganizationId());
        statement.setString(10, context.operatorDepartmentId());
        statement.setString(11, context.moduleAlias());
        statement.setString(12, context.actionCode());
        statement.setString(13, errorCode(event));
        statement.setString(14, loginOutcome(event));
        statement.setString(15, loginAccount(event));
        if (httpStatus(event) == null) {
            statement.setNull(16, java.sql.Types.INTEGER);
        } else {
            statement.setInt(16, httpStatus(event));
        }
        statement.setString(17, actionOutcome(event));
        statement.setString(18, actionEntityAlias(event));
        statement.setString(19, actionRecordId(event));
        statement.setString(20, actionMutationSource(event));
        statement.setString(21, serialize(event.details()));
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
                resultSet.getString("operator_account"),
                resultSet.getString("operator_organization_id"),
                resultSet.getString("operator_department_id"),
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

    private static void appendQueryFilters(StringBuilder sql, List<Object> parameters, BusinessLogQuery query) {
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
        appendFilter(sql, parameters, "action_outcome = ?", query.actionOutcome() == null ? null
                : query.actionOutcome().name());
        appendFilter(sql, parameters, "record_id = ?", query.recordId());
        appendFilter(sql, parameters, "mutation_source = ?", query.mutationSource() == null ? null
                : query.mutationSource().name());
    }

    private static void appendCandidateFilters(StringBuilder sql, List<Object> parameters,
                                               BusinessLogOperatorCandidateQuery query) {
        if (query.keyword() != null) {
            sql.append(" and (position(lower(?) in lower(operator_id)) > 0"
                    + " or position(lower(?) in lower(operator_account)) > 0)");
            parameters.add(query.keyword());
            parameters.add(query.keyword());
        }
        appendInFilter(sql, parameters, "operator_id", query.operatorIds());
        appendFilter(sql, parameters, "tenant_id = ?", query.tenantId());
        appendFilter(sql, parameters, "operator_organization_id = ?", query.organizationId());
        appendFilter(sql, parameters, "operator_department_id = ?", query.departmentId());
    }

    private List<BusinessLogOperatorNavigationItem> navigation(String sql, List<Object> parameters,
                                                                 boolean hasTenantColumn) {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, parameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<BusinessLogOperatorNavigationItem> values = new ArrayList<>();
                        while (resultSet.next()) {
                            String tenantId = hasTenantColumn ? resultSet.getString("tenant_id") : null;
                            String id = hasTenantColumn ? resultSet.getString(resultSet.getMetaData().getColumnCount())
                                    : resultSet.getString(1);
                            String organizationId = hasTenantColumn && resultSet.getMetaData().getColumnCount() == 3
                                    ? resultSet.getString("operator_organization_id") : null;
                            values.add(new BusinessLogOperatorNavigationItem(tenantId, organizationId, id));
                        }
                        return List.copyOf(values);
                    }
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("read business-log operator navigation", exception);
        }
    }

    private long countCandidates(String groupedQuery, List<Object> parameters) {
        String countSql = "select count(*) from (" + groupedQuery + ") candidates";
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                    bind(statement, parameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getLong(1);
                    }
                }
            });
        } catch (SQLException exception) {
            throw storageFailure("count business-log operator candidates", exception);
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

    private static String actionOutcome(BusinessLogEvent event) {
        return event instanceof ActionLogEvent action ? action.details().outcome().name() : null;
    }

    private static String actionEntityAlias(BusinessLogEvent event) {
        return event instanceof ActionLogEvent action ? action.details().entityAlias() : null;
    }

    private static String actionRecordId(BusinessLogEvent event) {
        return event instanceof ActionLogEvent action ? action.details().recordId() : null;
    }

    private static String actionMutationSource(BusinessLogEvent event) {
        return event instanceof ActionLogEvent action && action.details().mutationSource() != null
                ? action.details().mutationSource().name() : null;
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
