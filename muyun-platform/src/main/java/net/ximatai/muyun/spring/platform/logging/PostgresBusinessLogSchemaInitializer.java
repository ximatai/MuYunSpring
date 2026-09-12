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
 * <p>The initializer only creates absent append-only objects. It never alters or drops an
 * existing table, so an incompatible production schema fails during normal store use instead of
 * being silently migrated by a collector.</p>
 */
public class PostgresBusinessLogSchemaInitializer {
    private final DataSource dataSource;

    public PostgresBusinessLogSchemaInitializer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public void ensure() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
                        module_alias varchar(192),
                        action_code varchar(128),
                        error_code varchar(128),
                        details_json jsonb not null
                    )
                    """);
            statement.execute("create index if not exists business_log_event_occurred_idx "
                    + "on muyun_log.business_log_event (occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_tenant_occurred_idx "
                    + "on muyun_log.business_log_event (tenant_id, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_module_action_occurred_idx "
                    + "on muyun_log.business_log_event (module_alias, action_code, occurred_at desc, event_id desc)");
            statement.execute("create index if not exists business_log_event_error_occurred_idx "
                    + "on muyun_log.business_log_event (error_code, occurred_at desc, event_id desc)");
        } catch (SQLException exception) {
            throw new BusinessLogStorageException("Could not initialize business log schema", exception);
        }
    }
}
