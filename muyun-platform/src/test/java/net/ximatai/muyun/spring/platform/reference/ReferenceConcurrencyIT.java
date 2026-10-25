package net.ximatai.muyun.spring.platform.reference;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.deletion.*;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Two committed PostgreSQL transactions, paused at actual integrity-read and delete-guard boundaries. */
@SpringBootTest(classes = ReferenceConcurrencyIT.Host.class)
class ReferenceConcurrencyIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired IDatabaseOperations<?> db;
    @Autowired TargetDao targets;
    @Autowired RestrictDao restrictRows;
    @Autowired CascadeDao cascadeRows;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    private DynamicRecordRuntime runtime;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch contenderLockAttempt = new CountDownLatch(1);

    record Scenario(String target, String sources, ReferenceTargetUnavailablePolicy policy, boolean saveFirst) {
        boolean dynamicTarget() { return target.equals("dynamic"); }
    }
    static Stream<Scenario> scenarios() {
        var cases = new ArrayList<Scenario>();
        for (String target : List.of("static-hard", "static-soft", "dynamic"))
            for (String sources : List.of("static", "dynamic", "mixed"))
                for (var policy : List.of(ReferenceTargetUnavailablePolicy.RESTRICT, ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
                    for (boolean saveFirst : List.of(false, true)) cases.add(new Scenario(target, sources, policy, saveFirst));
        return cases.stream();
    }

    @AfterEach void reset() {
        release.countDown();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
        PlatformAbilityRuntime.resetReferenceDeletionGuard();
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        if (runtime != null) runtime.close();
    }

    @ParameterizedTest @MethodSource("scenarios")
    void saveAndDeleteSerializeAtReferenceTarget(Scenario scenario) throws Exception {
        runScenario(scenario, false);
    }

    static Stream<Scenario> concurrentSaves() {
        return scenarios().filter(s -> s.saveFirst() && s.policy() == ReferenceTargetUnavailablePolicy.RESTRICT);
    }

    @ParameterizedTest @MethodSource("concurrentSaves")
    void savesSharingTargetCommitWithoutWaitingForEachOther(Scenario scenario) throws Exception {
        runScenario(scenario, true);
    }

    private void runScenario(Scenario scenario, boolean concurrentSaves) throws Exception {
        var transaction = new TransactionTemplate(manager);
        var jdbc = new JdbcTemplate(dataSource);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return transaction.execute(status -> work.get()); }
            public void lock(String scope, String key) { lock(scope, key, false); }
            public void lockShared(String scope, String key) { lock(scope, key, true); }
            private void lock(String scope, String key, boolean shared) {
                if (Thread.currentThread().getName().equals("reference-contender")) contenderLockAttempt.countDown();
                jdbc.queryForObject(shared ? "select pg_advisory_xact_lock_shared(hashtextextended(?, 0))"
                        : "select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                        scope.length() + ":" + scope + ":" + key);
            }
        });
        var targetEntity = new EntityDefinition("entry", "concurrent_dynamic_target", "Target", List.of(FieldDefinition.titleField()))
                .withCapabilities(EntityCapability.REFERENCE);
        var sourceEntity = new EntityDefinition("link", "concurrent_dynamic_link", "Link", List.of(
                FieldDefinition.string("title", "Title"), new FieldDefinition("targetId", "target_id", FieldType.STRING, "Target")));
        var dynamicTargetModule = new ModuleDefinition("test.target", "Target", List.of(targetEntity));
        var dynamicSourceModule = ModuleDefinition.builder("test.source", "Source").entities(List.of(sourceEntity))
                .references(List.of(EntityReferenceDefinition.to("link", "targetId", "test.target.entry")
                        .withIntegrity(new ReferenceIntegrityPolicy(scenario.policy())))).build();
        new DynamicSchemaService(db).ensureModule(dynamicTargetModule);
        new DynamicSchemaService(db).ensureModule(dynamicSourceModule);
        for (String table : List.of("concurrent_static_target", "concurrent_restrict", "concurrent_cascade", "concurrent_dynamic_target", "concurrent_dynamic_link"))
            db.execute("delete from " + table);
        runtime = DynamicRecordRuntime.builder(db).build().register(dynamicTargetModule).register(dynamicSourceModule);
        ReferenceAbility<TargetRow> staticTarget = scenario.target().equals("static-hard")
                ? new HardTargetService(targets) : new TargetService(targets);
        var staticSource = scenario.policy() == ReferenceTargetUnavailablePolicy.RESTRICT
                ? new LinkService<RestrictLink>(restrictRows, RestrictLink.class) : new LinkService<CascadeLink>(cascadeRows, CascadeLink.class);
        CrudAbility<?> target = scenario.dynamicTarget() ? runtime.entityService("test.target", "entry") : staticTarget;
        ReferenceAbility<?> targetReads = spy(scenario.dynamicTarget()
                ? runtime.entityService("test.target", "entry").referenceAbility() : staticTarget);
        PlatformAbilityRuntime.configureReferenceTargetResolver(ref -> ref.equals(ReferenceTarget.of("test.target", "entry"))
                ? Optional.of(targetReads) : Optional.empty());
        var guard = new CompositeReferenceDeletionGuard(List.of(new StaticReferenceDeletionGuard(List.of(staticSource)),
                new DynamicReferenceDeletionGuard(runtime)));
        PlatformAbilityRuntime.configureReferenceDeletionGuard(new ReferenceDeletionGuard() {
            public void validateTargetUnavailable(CrudAbility<?> ability, EntityContract record) {
                guard.validateTargetUnavailable(ability, record);
                if (!scenario.saveFirst() && record.getId().equals("target")) pause();
            }
            public void cascadeTargetUnavailable(CrudAbility<?> ability, EntityContract record, DeletionContext context, DeletionNode node, DeletionMode mode) {
                guard.cascadeTargetUnavailable(ability, record, context, node, mode);
            }
        });
        inTenant(() -> {
            if (scenario.dynamicTarget()) {
                var row = runtime.newRecord("test.target", "entry").setValue("title", "Target"); row.setId("target");
                runtime.entityService("test.target", "entry").insert(row);
            } else { var row = new TargetRow(); row.setId("target"); row.setTitle("Target"); staticTarget.insert(row); }
            return null;
        });
        doAnswer(call -> {
            Object facts = call.callRealMethod();
            if (scenario.saveFirst() && !(concurrentSaves && Thread.currentThread().getName().equals("reference-contender"))) pause();
            return facts;
        }).when(targetReads).referenceFacts(anyCollection(), anyCollection());
        Supplier<Object> save = () -> inTenant(() -> {
            // A mixed save is one transaction; both sources must commit or roll back together.
            return transaction.execute(status -> {
                String suffix = concurrentSaves && Thread.currentThread().getName().equals("reference-contender") ? "-second" : "";
                if (!scenario.sources().equals("dynamic")) staticSource.saveLink("static-link" + suffix);
                if (!scenario.sources().equals("static")) {
                    var row = runtime.newRecord("test.source", "link").setValue("title", "Link").setValue("targetId", "target");
                    row.setId("dynamic-link" + suffix); runtime.entityService("test.source", "link").insert(row);
                }
                return null;
            });
        });
        Supplier<Object> delete = () -> inTenant(() -> target.delete("target"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = pool.submit(() -> outcome(scenario.saveFirst() ? save : delete));
            assertThat(reached.await(10, TimeUnit.SECONDS)).as("first transaction reached the database boundary").isTrue();
            var started = new CountDownLatch(1);
            Future<Throwable> second = pool.submit(() -> {
                Thread.currentThread().setName("reference-contender");
                started.countDown(); return outcome(concurrentSaves ? save : scenario.saveFirst() ? delete : save);
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                // The competing transaction cannot pass the protected boundary before the first commits.
                assertThat(contenderLockAttempt.await(10, TimeUnit.SECONDS)).as("contender attempted target lock").isTrue();
                if (concurrentSaves) {
                    assertThat(second.get(5, TimeUnit.SECONDS)).as("second save commits while first holds shared lock").isNull();
                    assertThat(first.isDone()).as("first transaction remains uncommitted").isFalse();
                    String table = scenario.sources().equals("dynamic") ? "concurrent_dynamic_link" : "concurrent_restrict";
                    assertThat(jdbc.queryForObject("select count(*) from " + table + " where deleted=false", Long.class))
                            .as("only second transaction is committed").isEqualTo(1);
                } else {
                    assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                }
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
            Throwable failure = second.get(10, TimeUnit.SECONDS);
            if (concurrentSaves) {
                inTenant(() -> {
                    assertThat(target.select("target")).isNotNull();
                    assertThat(staticSource.count(net.ximatai.muyun.database.core.orm.Criteria.of()))
                            .isEqualTo(scenario.sources().equals("dynamic") ? 0 : 2);
                    assertThat(runtime.entityService("test.source", "link").count(net.ximatai.muyun.database.core.orm.Criteria.of()))
                            .isEqualTo(scenario.sources().equals("static") ? 0 : 2);
                    return null;
                });
                return;
            }
            String targetTable = scenario.dynamicTarget() ? "concurrent_dynamic_target" : "concurrent_static_target";
            for (String table : List.of("concurrent_restrict", "concurrent_cascade", "concurrent_dynamic_link")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table + " s left join " + targetTable
                        + " t on s.target_id=t.id where s.deleted=false and (t.id is null or t.deleted=true)", Long.class))
                        .as("active dangling references in %s", table).isZero();
            }
            if (!scenario.saveFirst() || scenario.policy() == ReferenceTargetUnavailablePolicy.RESTRICT)
                assertThat(failure).isInstanceOf(PlatformException.class);
            else assertThat(failure).isNull();
        } finally { release.countDown(); }
        inTenant(() -> {
            boolean targetSurvives = scenario.saveFirst() && scenario.policy() == ReferenceTargetUnavailablePolicy.RESTRICT;
            assertThat(target.select("target") != null).isEqualTo(targetSurvives);
            assertThat(staticSource.count(net.ximatai.muyun.database.core.orm.Criteria.of()))
                    .isEqualTo(targetSurvives && !scenario.sources().equals("dynamic") ? 1 : 0);
            assertThat(runtime.entityService("test.source", "link").count(net.ximatai.muyun.database.core.orm.Criteria.of()))
                    .isEqualTo(targetSurvives && !scenario.sources().equals("static") ? 1 : 0);
            // Inspect active physical rows too: read scopes or caches must not hide dangling references.
            for (String table : List.of("concurrent_restrict", "concurrent_cascade", "concurrent_dynamic_link")) {
                long active = jdbc.queryForObject("select count(*) from " + table + " where deleted=false", Long.class);
                if (!targetSurvives) assertThat(active).isZero();
            }
            return null;
        });
    }

    private void pause() {
        reached.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static Throwable outcome(Supplier<?> work) { try { work.get(); return null; } catch (RuntimeException failure) { return failure; } }
    private static <T> T inTenant(Supplier<T> work) { try (var scope = TenantContext.use("tenant")) { return work.get(); } }

    @Table(name = "concurrent_static_target") public static class TargetRow extends StandardTitledEntity {}
    @Getter @Setter @Table(name = "concurrent_restrict") public static class RestrictLink extends StandardTitledEntity {
        @Column(name = "target_id", type = ColumnType.VARCHAR, length = 32)
        @ReferenceTo(moduleAlias = "test.target", entityAlias = "entry", integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
        private String targetId;
    }
    @Getter @Setter @Table(name = "concurrent_cascade") public static class CascadeLink extends StandardTitledEntity {
        @Column(name = "target_id", type = ColumnType.VARCHAR, length = 32)
        @ReferenceTo(moduleAlias = "test.target", entityAlias = "entry", integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
        private String targetId;
    }
    @net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository
    public interface TargetDao extends BaseDao<TargetRow, String> {}
    @net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository
    public interface RestrictDao extends BaseDao<RestrictLink, String> {}
    @net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository
    public interface CascadeDao extends BaseDao<CascadeLink, String> {}
    record HardTargetService(TargetDao dao) implements ReferenceAbility<TargetRow> {
        public BaseDao<TargetRow, String> getDao() { return dao; }
        public String getModuleAlias() { return "test.target.entry"; }
        public Class<?> modelClass() { return TargetRow.class; }
    }
    record TargetService(TargetDao dao) implements ReferenceAbility<TargetRow>, SoftDeleteAbility<TargetRow> {
        public BaseDao<TargetRow, String> getDao() { return dao; }
        public String getModuleAlias() { return "test.target.entry"; }
        public Class<?> modelClass() { return TargetRow.class; }
    }
    record LinkService<T extends StandardTitledEntity>(BaseDao<T, String> dao, Class<T> type) implements CrudAbility<T>, SoftDeleteAbility<T> {
        public BaseDao<T, String> getDao() { return dao; }
        public String getModuleAlias() { return "test.static_link"; }
        public Class<?> modelClass() { return type; }
        void saveLink(String id) {
            StandardTitledEntity row;
            if (type == RestrictLink.class) { var link = new RestrictLink(); link.setTargetId("target"); row = link; }
            else { var link = new CascadeLink(); link.setTargetId("target"); row = link; }
            row.setId(id); row.setTitle("Link"); insert(type.cast(row));
        }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = ReferenceConcurrencyIT.class)
    static class Host {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    }
}
