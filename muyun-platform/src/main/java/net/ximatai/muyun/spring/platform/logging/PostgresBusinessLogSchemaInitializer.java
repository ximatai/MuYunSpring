package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogStorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Controlled schema bootstrap for the PostgreSQL business log store.
 *
 * <p>The initializer creates immutable fact storage, runtime retention policy storage and safe
 * additive indexes or columns. It never drops or rewrites historical facts during schema evolution.</p>
 */
public class PostgresBusinessLogSchemaInitializer {
    private final DataSource dataSource;

    public PostgresBusinessLogSchemaInitializer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public void ensure() {
        try (Connection connection = dataSource.getConnection()) {
            ensure(connection);
        } catch (SQLException exception) {
            throw new BusinessLogStorageException("Could not initialize business log schema", exception);
        }
    }

    static void ensure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists muyun_log");
            statement.execute("""
                    create table if not exists muyun_log.business_log_event (
                        event_id varchar(128) primary key,
                        event_type varchar(32) not null,
                        occurred_at timestamptz not null,
                        captured_at timestamptz not null,
                        trace_id varchar(128) not null,
                        tenant_id varchar(128),
                        operator_id varchar(128),
                        operator_account varchar(256),
                        operator_organization_id varchar(128),
                        operator_department_id varchar(128),
                        module_alias varchar(192),
                        action_code varchar(128),
                        error_code varchar(128),
                        login_outcome varchar(32),
                        login_account varchar(256),
                        http_status integer,
                        action_outcome varchar(32),
                        entity_alias varchar(192),
                        record_id varchar(128),
                        mutation_source varchar(32),
                        details_json jsonb not null
                    )
                    """);
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists operator_account varchar(256)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists operator_organization_id varchar(128)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists operator_department_id varchar(128)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists login_outcome varchar(32)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists login_account varchar(256)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists http_status integer");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists action_outcome varchar(32)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists entity_alias varchar(192)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists record_id varchar(128)");
            statement.execute("alter table muyun_log.business_log_event "
                    + "add column if not exists mutation_source varchar(32)");
            statement.execute("create index if not exists business_log_event_occurred_idx "
                    + "on muyun_log.business_log_event (occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_type_occurred_idx "
                    + "on muyun_log.business_log_event (event_type, occurred_at, event_id)");
            statement.execute("create index if not exists business_log_event_tenant_occurred_idx "
                    + "on muyun_log.business_log_event (tenant_id, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_tenant_operator_organization_occurred_idx "
                    + "on muyun_log.business_log_event "
                    + "(tenant_id, operator_organization_id, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_tenant_operator_department_occurred_idx "
                    + "on muyun_log.business_log_event "
                    + "(tenant_id, operator_department_id, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_operator_account_occurred_idx "
                    + "on muyun_log.business_log_event (operator_account, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_module_action_occurred_idx "
                    + "on muyun_log.business_log_event (module_alias, action_code, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_error_occurred_idx "
                    + "on muyun_log.business_log_event (error_code, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_login_outcome_occurred_idx "
                    + "on muyun_log.business_log_event (login_outcome, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_login_account_occurred_idx "
                    + "on muyun_log.business_log_event (login_account, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_http_status_occurred_idx "
                    + "on muyun_log.business_log_event (http_status, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_action_outcome_occurred_idx "
                    + "on muyun_log.business_log_event (action_outcome, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_module_record_occurred_idx "
                    + "on muyun_log.business_log_event (module_alias, record_id, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_mutation_source_occurred_idx "
                    + "on muyun_log.business_log_event (mutation_source, occurred_at desc, event_id desc)");
            statement.execute("""
                    create table if not exists muyun_log.business_log_retention_policy (
                        event_type varchar(32) primary key,
                        automatic_cleanup_enabled boolean not null default false,
                        retention_days integer not null,
                        updated_at timestamptz,
                        updated_by varchar(128),
                        constraint business_log_retention_days_check
                            check (retention_days between 1 and 36500)
                    )
                    """);
            statement.execute("""
                    insert into muyun_log.business_log_retention_policy
                        (event_type, automatic_cleanup_enabled, retention_days)
                    values
                        ('LOGIN', false, 180),
                        ('ACTION', false, 180),
                        ('REQUEST_ERROR', false, 180),
                        ('PAGE_ACCESS', false, 180)
                    on conflict (event_type) do nothing
                    """);
        }
    }
}
