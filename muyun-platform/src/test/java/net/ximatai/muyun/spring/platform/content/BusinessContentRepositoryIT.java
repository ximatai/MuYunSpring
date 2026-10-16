package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = BusinessContentRepositoryIT.TestApplication.class)
class BusinessContentRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired ContentFixtureDao dao;
    @Autowired BusinessContentStore store;
    @Autowired BusinessContentService content;
    @Autowired BusinessContentSaveLifecycleListener listener;
    @Autowired BusinessContentDeletionLifecycleListener deletion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IDatabaseOperations<?> operations;
    private FixtureService service;
    private TransactionTemplate tx;
    private static final PageRequest HISTORY = PageRequest.of(1, 100);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @BeforeEach void install() {
        tx = new TransactionTemplate(transactions);
        service = new FixtureService(dao);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(java.util.function.Supplier<T> work) { return tx.execute(status -> work.get()); }
        });
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new CompositeEntitySaveLifecycleListener(List.of(listener)));
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new net.ximatai.muyun.spring.ability.deletion.CompositeDeletionLifecycleListener(List.of(deletion)));
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
    }
    @AfterEach void reset() {
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
        PlatformAbilityRuntime.resetDeletionLifecycleListener();
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        PlatformAbilityRuntime.resetDataScopeCriteriaService();
    }
    @Test void keepsRealOriginalActorLanguageAndIndependentTextRevisionsAcrossReopen() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("数控设备");
            try (var input = input(row, "zh-CN", null)) { service.insert(row); }
            var first = history(row).getFirst();
            assertThat(first.text()).isEqualTo("数控设备");
            assertThat(first.sourceLanguage()).isEqualTo("zh-CN");
            assertThat(first.authorId()).isEqualTo("cn");
            var reopened = service.select(row.getId());
            reopened.setQuantity(12);
            service.update(reopened);
            assertThat(history(row)).hasSize(1);
            var stale = service.select(row.getId());
            try (var american = actor("us")) {
                reopened = service.select(row.getId());
                reopened.setTitle("CNC equipment");
                try (var input = input(reopened, "en-US", first.revision())) { service.update(reopened); }
            }
            assertThat(service.select(row.getId()).getTitle()).isEqualTo("CNC equipment");
            assertThat(history(row)).extracting(BusinessContentService.SourceRevision::text).containsExactly("CNC equipment", "数控设备");
            assertThat(history(row).getFirst().authorId()).isEqualTo("us");
            stale.setTitle("stale overwrite");
            assertThatThrownBy(() -> service.update(stale)).isInstanceOf(OptimisticLockException.class);
            assertThat(history(row)).hasSize(2);
            var current = service.select(row.getId());
            try (var input = input(current, "de-DE", first.revision())) {
                assertThatThrownBy(() -> service.update(current)).isInstanceOf(OptimisticLockException.class);
            }
            assertThat(history(row)).hasSize(2);
            var correction = service.select(row.getId());
            try (var input = input(correction, "en", 2)) { service.update(correction); }
            assertThat(history(row)).hasSize(3);
            assertThat(history(row).getFirst().sourceLanguage()).isEqualTo("en");
        }
    }
    @Test void sourceCaptureRollsBackWithBusinessWriteAndCannotBeReadAfterDeleteOrAcrossTenants() {
        String id;
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("原文"); service.insert(row); id = row.getId();
            assertThat(history(row).getFirst().sourceLanguage()).isNull();
            tx.executeWithoutResult(status -> {
                var edit = service.select(id); edit.setTitle("rollback"); service.update(edit);
                assertThat(history(row)).hasSize(2);
                status.setRollbackOnly();
            });
            assertThat(service.select(id).getTitle()).isEqualTo("原文");
            assertThat(history(row)).hasSize(1);
            var deniedScope = new FixtureService(dao) {
                @Override public DataScopeCriteriaResult readScope(PlatformAction action, Criteria criteria) {
                    return DataScopeCriteriaResult.unrestricted(criteria.eq("id", "invisible"));
                }
            };
            assertThatThrownBy(() -> content.history(deniedScope, id, "title", HISTORY)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
        try (var user = actor("other"); var tenant = TenantContext.use("tenant-b")) {
            assertThatThrownBy(() -> content.history(service, id, "title", HISTORY)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            service.delete(id);
            assertThatThrownBy(() -> content.history(service, id, "title", HISTORY)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            service.restore(id);
            assertThat(content.history(service, id, "title", HISTORY)).hasSize(1);
            var hard = new AbstractAbilityService<ContentFixture>("test.content", ContentFixture.class, dao) { };
            var source = BusinessContentSaveLifecycleListener.source(service, service.select(id), "category-name");
            tx.executeWithoutResult(status -> { hard.delete(id); assertThat(store.latest(source)).isNull(); status.setRollbackOnly(); });
            assertThat(content.history(service, id, "title", HISTORY)).hasSize(1);
            hard.delete(id);
            assertThat(store.latest(source)).isNull();
        }
    }
    @Test void concurrentOriginalEditsCannotProduceTwoCurrentRevisions() throws Exception {
        String id;
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("initial"); service.insert(row); id = row.getId();
        }
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> writes = new ArrayList<>();
            for (String value : List.of("English", "Deutsch")) writes.add(executor.submit(() -> {
                try (var user = actor(value); var tenant = TenantContext.use("tenant-a")) {
                    var row = service.select(id); row.setTitle(value); start.await(10, TimeUnit.SECONDS);
                    try { service.update(row); return true; }
                    catch (OptimisticLockException expected) { return false; }
                }
            }));
            assertThat(List.of(writes.get(0).get(15, TimeUnit.SECONDS), writes.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            assertThat(content.history(service, id, "title", HISTORY)).hasSize(2);
        }
    }
    @Test void dynamicAggregateCapturesDifferentFieldLanguagesAndOmittedValuesRemainUntouched() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var policy = new ContentFieldDefinition("description-stable", ContentPolicy.AUTO_TRANSLATE);
        var main = new EntityDefinition("main", "content_main_" + suffix, "Quality exception", List.of(
                FieldDefinition.text("description", "Description").content(policy), FieldDefinition.integer("quantity", "Quantity")));
        var line = new EntityDefinition("line", "content_line_" + suffix, "Investigation", List.of(
                FieldDefinition.string("mainId", "Parent").column("main_id").length(32).required(),
                FieldDefinition.text("description", "Investigation").content(policy)));
        var module = ModuleDefinition.builder("test.content_" + suffix, "Content").entities(List.of(main, line))
                .relations(List.of(EntityRelationDefinition.child("lines", "main", "line", "mainId").withAutoPopulate())).build();
        new DynamicSchemaService(operations).ensureModule(module);
        try (var runtime = new DynamicRecordRuntime(operations).register(module);
             var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var records = new DynamicRecordService(runtime);
            var parent = new DynamicRecord(main).setValue("description", "80°C，暂勿发货");
            var child = new DynamicRecord(line).setValue("description", "Investigation pending");
            parent.setChildren("lines", List.of(child));
            try (var parentInput = ContentWriteContext.open(parent, Map.of("description", new ContentWriteContext.SourceInput("zh-CN", null)));
                 var childInput = ContentWriteContext.open(child, Map.of("description", new ContentWriteContext.SourceInput("en-US", null)))) {
                records.create(module.moduleAlias(), "main", parent);
            }
            var parentHistory = content.history(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "description", HISTORY);
            var childHistory = content.history(runtime.entityService(module.moduleAlias(), "line"), child.getId(), "description", HISTORY);
            assertThat(parentHistory.getFirst().sourceLanguage()).isEqualTo("zh-CN");
            assertThat(childHistory.getFirst().sourceLanguage()).isEqualTo("en-US");
            assertThat(parentHistory.getFirst().sourceKey()).isNotEqualTo(childHistory.getFirst().sourceKey());
            var reopened = records.select(module.moduleAlias(), "main", parent.getId());
            assertThat(reopened.getChildren("lines").getFirst().getValue("description")).isEqualTo("Investigation pending");
            var partial = new DynamicRecord(main).setValue("quantity", 0);
            partial.setId(parent.getId()); partial.setVersion(reopened.getVersion());
            records.update(module.moduleAlias(), "main", partial);
            assertThat(content.history(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "description", HISTORY)).hasSize(1);
            assertThat(records.select(module.moduleAlias(), "main", parent.getId()).getValue("description")).isEqualTo("80°C，暂勿发货");
        }
    }
    private static ContentFixture row(String title) { var row = new ContentFixture(); row.setTitle(title); return row; }
    private CurrentUserContext.Scope actor(String name) { return CurrentUserContext.use(CurrentUser.tenantUser(name, name, "tenant-a")); }
    private static ContentWriteContext.Scope input(ContentFixture row, String language, Integer revision) {
        return ContentWriteContext.open(row, Map.of("title", new ContentWriteContext.SourceInput(language, revision)));
    }
    private List<BusinessContentService.SourceRevision> history(ContentFixture row) { return content.history(service, row.getId(), "title", HISTORY); }
    static class FixtureService extends AbstractAbilityService<ContentFixture> implements SoftDeleteAbility<ContentFixture>, DataScopeAbility<ContentFixture> {
        FixtureService(ContentFixtureDao dao) { super("test.content", ContentFixture.class, dao); }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = BusinessContentRevisionDao.class)
    @Import({BusinessContentStore.class, BusinessContentService.class, BusinessContentSaveLifecycleListener.class, BusinessContentDeletionLifecycleListener.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ActionExecutionPolicyService policies() { return new AllowAllActionExecutionPolicyService(); }
        @Bean DataScopeCriteriaService scopes() { return new AllowAllDataScopeCriteriaService(); }
    }
}
