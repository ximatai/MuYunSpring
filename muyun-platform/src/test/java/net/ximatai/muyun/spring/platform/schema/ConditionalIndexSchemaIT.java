package net.ximatai.muyun.spring.platform.schema;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.spring.boot.sql.annotation.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.*;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import static net.ximatai.muyun.spring.common.model.constraint.IndexCondition.Operator.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ConditionalIndexSchemaIT.Host.class)
class ConditionalIndexSchemaIT extends PlatformPostgresIntegrationTest {
    @Autowired IDatabaseOperations<?> db;
    @Autowired Rows rows;
    private final EntityDefinition entity = new EntityDefinition("entry", "conditional_dynamic", "Entries", List.of(
            FieldDefinition.string("assetCode", "Asset").column("asset_code"),
            FieldDefinition.timestamp("endedAt", "End").column("ended_at"),
            FieldDefinition.string("state", "State")))
            .withConditionalIndexes(
                    new ConditionalIndexDefinition("ux_conditional_dynamic_active", List.of("assetCode"), true,
                            List.of(new IndexCondition("endedAt", IS_NULL)), "active asset already exists"),
                    new ConditionalIndexDefinition("ix_conditional_dynamic_recovery", List.of("tenantId", "state"), false,
                            List.of(new IndexCondition("deleted", IS_FALSE), new IndexCondition("state", IN_CODES, List.of("READY", "RUNNING")))));

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @BeforeEach void ensure() {
        new StaticSchemaService(db).ensureTable(Row.class);
        new DynamicSchemaService(db).ensureTable(entity);
        db.execute("delete from conditional_static");
        db.execute("delete from conditional_dynamic");
    }

    @Test void staticAndDynamicPredicatesAreStableAndIdentical() {
        var staticTable = new StaticEntityTableMapper().toTable(Row.class);
        var dynamicTable = new DynamicTableMapper().toTable(entity);
        assertThat(staticTable.getIndexes().stream().map(index -> List.of(index.getColumns(), index.getPredicate(), index.isUnique())).toList())
                .containsExactlyInAnyOrderElementsOf(dynamicTable.getIndexes().stream().map(index -> List.of(index.getColumns(), index.getPredicate(), index.isUnique())).toList());
        assertThat(new StaticSchemaService(db).ensureTable(Row.class, MigrationOptions.dryRun()).getStatements()).isEmpty();
        assertThat(new DynamicSchemaService(db).ensureTable(entity, MigrationOptions.dryRun()).getStatements()).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void domainPreflightAndDatabaseAgreeOnConditionalUniqueness(boolean dynamic) {
        var module = new ModuleDefinition("sample.indexed", "Entries", List.of(entity));
        try (var runtime = DynamicRecordRuntime.builder(db).build().register(module)) {
            var service = dynamic ? runtime.entityService("sample.indexed", "entry") : new StaticService(rows);
            try (var tenant = TenantContext.use("a")) {
                insert(service, dynamic, "active", "code", null);
                insert(service, dynamic, "historical", "code", Instant.parse("2020-01-01T00:00:00Z"));
                assertThatThrownBy(() -> insert(service, dynamic, "duplicate", "code", null))
                        .isInstanceOf(PlatformException.class).hasMessage("active asset already exists");
                String table = dynamic ? "conditional_dynamic" : "conditional_static";
                assertThatThrownBy(() -> db.execute("insert into " + table + " (id,tenant_id,asset_code) values ('direct','a','code')"))
                        .isInstanceOf(RuntimeException.class);
                // Soft deletion alone must not release an unfinished relationship.
                service.delete("active");
                assertThatThrownBy(() -> insert(service, dynamic, "still-held", "code", null))
                        .isInstanceOf(PlatformException.class).hasMessageContaining("soft-deleted");
                db.execute("update " + table + " set ended_at=? where id='active'", Instant.parse("2021-01-01T00:00:00Z"));
                insert(service, dynamic, "released", "code", null);
            }
            try (var tenant = TenantContext.use("b")) { insert(service, dynamic, "other-tenant", "code", null); }
        }
    }

    @Test void predicateChangesAreVisibleAndStrictMigrationPreservesTheExistingIndex() {
        var changed = entity.withConditionalIndexes(new ConditionalIndexDefinition("ux_conditional_dynamic_active",
                List.of("assetCode"), true, List.of(new IndexCondition("endedAt", IS_NOT_NULL))), entity.conditionalIndexes().get(1));
        var schema = new DynamicSchemaService(db);
        var plan = schema.ensureTable(changed, MigrationOptions.dryRun());
        assertThat(plan.isChanged()).isTrue();
        assertThat(plan.hasNonAdditiveChanges()).isTrue();
        assertThat(plan.getStatements()).anyMatch(sql -> sql.toLowerCase().contains("drop index"));
        assertThatThrownBy(() -> schema.ensureTable(changed, MigrationOptions.strict())).isInstanceOf(RuntimeException.class);
        assertThat(schema.ensureTable(entity, MigrationOptions.dryRun()).getStatements()).isEmpty();
    }

    @Test void removingADeclaredIndexDoesNotRemoveUnmanagedIndexes() {
        db.execute("create index ix_unmanaged on conditional_dynamic(state)");
        try {
            var reduced = entity.withConditionalIndexes(entity.conditionalIndexes().getFirst());
            var schema = new DynamicSchemaService(db);
            var plan = schema.ensureTable(reduced, entity, MigrationOptions.dryRun());
            assertThat(plan.hasNonAdditiveChanges()).isTrue();
            assertThat(plan.getStatements()).singleElement().asString().contains("ix_conditional_dynamic_recovery");
            assertThatThrownBy(() -> schema.ensureTable(reduced, entity, MigrationOptions.strict())).isInstanceOf(RuntimeException.class);
            schema.ensureTable(reduced, entity, MigrationOptions.execute());
            assertThat(schema.ensureTable(reduced, entity, MigrationOptions.dryRun()).isChanged()).isFalse();
            assertThat(db.row("select to_regclass('public.ix_unmanaged') as name").get("name")).isNotNull();
        } finally { db.execute("drop index ix_unmanaged"); }
    }

    @Test void removingConditionalUniqueConstraintUsesItsPublicDeclarationIdentity() {
        var previous = entity.withConditionalIndexes(entity.conditionalIndexes().get(1))
                .withTenantUniqueConstraints(entity.conditionalIndexes().getFirst().uniqueConstraint());
        var target = previous.withTenantUniqueConstraints();
        var schema = new DynamicSchemaService(db);
        var plan = schema.ensureTable(target, previous, MigrationOptions.dryRun());
        assertThat(plan.getStatements()).singleElement().asString().contains("ux_conditional_dynamic_active");
        assertThatThrownBy(() -> schema.ensureTable(target, previous, MigrationOptions.strict())).isInstanceOf(RuntimeException.class);
        schema.ensureTable(target, previous, MigrationOptions.execute());
        assertThat(schema.ensureTable(target, previous, MigrationOptions.dryRun()).isChanged()).isFalse();
        db.execute("insert into conditional_dynamic(id,tenant_id,asset_code) values ('removed-one','a','same'),('removed-two','a','same')");
        db.execute("delete from conditional_dynamic");
    }

    @Test void addingUniqueIndexToDuplicateDataFailsWithoutDeletingBusinessRows() {
        db.execute("drop index ux_conditional_dynamic_active");
        db.execute("insert into conditional_dynamic(id,tenant_id,asset_code) values ('one','a','duplicate'),('two','a','duplicate')");
        assertThat(new DynamicSchemaService(db).ensureTable(entity, MigrationOptions.dryRun()).getStatements())
                .anyMatch(sql -> sql.toLowerCase().contains("unique index"));
        assertThatThrownBy(() -> new DynamicSchemaService(db).ensureTable(entity)).isInstanceOf(RuntimeException.class);
        assertThat(((Number) db.row("select count(*) as n from conditional_dynamic").get("n")).intValue()).isEqualTo(2);
        db.execute("delete from conditional_dynamic");
        new DynamicSchemaService(db).ensureTable(entity);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void insert(CrudAbility service, boolean dynamic, String id, String code, Instant ended) {
        if (dynamic) {
            DynamicRecord record = new DynamicRecord(entity); record.setId(id); record.setValue("assetCode", code); record.setValue("endedAt", ended); record.setValue("state", "READY");
            service.insert(record);
        } else {
            Row record = new Row(); record.setId(id); record.setAssetCode(code); record.setEndedAt(ended); record.setState("READY"); service.insert(record);
        }
    }

    @Getter @Setter @Table(name = "conditional_static")
    @ConditionalIndex(name = "ux_conditional_static_active", fields = "assetCode", unique = true, message = "active asset already exists",
            when = @ConditionalIndex.Condition(field = "endedAt", operator = IS_NULL))
    @ConditionalIndex(name = "ix_conditional_static_recovery", fields = {"tenantId", "state"}, when = {
            @ConditionalIndex.Condition(field = "deleted", operator = IS_FALSE),
            @ConditionalIndex.Condition(field = "state", operator = IN_CODES, values = {"READY", "RUNNING"})})
    public static class Row extends StandardEntity {
        @Column(name = "asset_code", type = ColumnType.VARCHAR, length = 255) private String assetCode;
        @Column(name = "ended_at", type = ColumnType.TIMESTAMP) private Instant endedAt;
        @Column(name = "state", type = ColumnType.VARCHAR, length = 255) private String state;
    }
    @MuYunRepository public interface Rows extends BaseDao<Row, String> { }
    static class StaticService extends StandardBusinessService<Row> implements SoftDeleteAbility<Row> {
        StaticService(Rows rows) { super("sample.indexed", Row.class, rows); }
    }
    @SpringBootConfiguration @EnableAutoConfiguration @EnableMuYunRepositories(basePackageClasses = ConditionalIndexSchemaIT.class)
    static class Host {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    }
}
