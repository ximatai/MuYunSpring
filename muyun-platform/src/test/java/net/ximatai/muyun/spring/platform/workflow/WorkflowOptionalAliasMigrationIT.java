package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOptionalAliasMigrationIT extends PlatformPostgresIntegrationTest {
    @Test
    void makesAliasesOptionalWithoutChangingLegacyValuesAndCanRunAgain() throws Exception {
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var command = admin.createStatement()) {
            command.execute("CREATE DATABASE " + database);
            try (var connection = DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/" + database, postgres.getUsername(), postgres.getPassword());
                 var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE platform_workflow_definition (id text primary key, alias text not null)");
                sql.execute("INSERT INTO platform_workflow_definition VALUES ('legacy', 'sync')");
                var migration = Files.readString(Path.of("../scripts/migrations/workflow-definition-optional-alias-postgresql.sql"));
                sql.execute(migration);
                sql.execute("INSERT INTO platform_workflow_definition VALUES ('first', null), ('second', null)");
                sql.execute(migration);
                try (var row = sql.executeQuery("SELECT alias FROM platform_workflow_definition WHERE id = 'legacy'")) {
                    row.next(); assertThat(row.getString(1)).isEqualTo("sync");
                }
                try (var row = sql.executeQuery("SELECT count(*) FROM platform_workflow_definition WHERE alias IS NULL")) {
                    row.next(); assertThat(row.getInt(1)).isEqualTo(2);
                }
            } finally { command.execute("DROP DATABASE " + database); }
        }
    }
}
