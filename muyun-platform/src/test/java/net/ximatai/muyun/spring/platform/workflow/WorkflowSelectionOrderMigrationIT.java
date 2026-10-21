package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSelectionOrderMigrationIT extends PlatformPostgresIntegrationTest {
    @Test
    void legacyPriorityPreservesGlobalAndTenantSelectionOrderExactlyOnce() throws Exception {
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var command = admin.createStatement()) {
            command.execute("CREATE DATABASE " + database);
            try (var connection = DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/" + database, postgres.getUsername(), postgres.getPassword());
                 var sql = connection.createStatement()) {
                sql.execute("""
                        CREATE TABLE platform_workflow_definition (
                          id text primary key, tenant_id text, module_alias text, alias text,
                          match_priority integer, sort_order integer, version integer);
                        INSERT INTO platform_workflow_definition VALUES
                          ('a', null, 'test.one', 'a', 0, 100, 2),
                          ('b', null, 'test.one', 'b', 10, 200, 3),
                          ('c', null, 'test.one', 'c', 10, 50, 4),
                          ('d', 'tenant-a', 'test.one', 'd', -3, 10, 5),
                          ('e', 'tenant-a', 'test.one', 'e', 100, 10, 6),
                          ('f', null, 'test.two', 'f', null, null, 7);
                        """);
                var script = Path.of("../scripts/migrations/workflow-selection-order-postgresql.sql");
                sql.execute(Files.readString(script));
                var orders = new ArrayList<String>();
                try (var rows = sql.executeQuery("SELECT id || ':' || sort_order || ':' || version FROM platform_workflow_definition ORDER BY id")) {
                    while (rows.next()) orders.add(rows.getString(1));
                }
                assertThat(orders).containsExactly("a:400:3", "b:300:4", "c:200:5", "d:500:6", "e:100:7", "f:100:8");
                // Re-running after an operator changes order must never restore the old priority.
                sql.execute("UPDATE platform_workflow_definition SET sort_order = 900 WHERE id = 'c'");
                sql.execute(Files.readString(script));
                try (var rows = sql.executeQuery("SELECT sort_order, version FROM platform_workflow_definition WHERE id = 'c'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(900); assertThat(rows.getInt(2)).isEqualTo(5);
                }
            } finally { command.execute("DROP DATABASE " + database); }
        }
    }
}
