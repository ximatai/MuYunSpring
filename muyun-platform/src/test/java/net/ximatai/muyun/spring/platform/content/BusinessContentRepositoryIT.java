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
    @Autowired BusinessContentDeletionObserver deletion;
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
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new net.ximatai.muyun.spring.ability.deletion.CompositeDeletionLifecycleListener(
                net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener.NONE, List.of(deletion)));
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
            assertThat(history(row).getFirst().authorId()).isEqualTo("us");
            assertThat(history(row).getFirst().revisionAuthorId()).isEqualTo("cn");
            assertThat(history(row).getFirst().authorName()).isEqualTo("us");
            assertThat(history(row).getFirst().revisionAuthorName()).isEqualTo("cn");
            assertThat(history(row).getFirst().revisionKind()).isEqualTo("SOURCE_LANGUAGE_CORRECTION");
            assertThat(history(row).getFirst().authoredAt()).isEqualTo(history(row).get(1).authoredAt());
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
    @Test void staticServiceWithoutDataScopeKeepsItsOwningReadContract() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var unscoped = new AbstractAbilityService<ContentFixture>("test.content", ContentFixture.class, dao) { };
            var row = row("Static source");
            row.contentLanguageInputs(Map.of("title", new ContentWriteContext.SourceInput("en", null)));
            unscoped.insert(row);
            assertThat(row.contentLanguageInputs()).isEmpty();
            row.setQuantity(12); unscoped.update(row);
            var unexpectedScopes = org.mockito.Mockito.mock(DataScopeCriteriaService.class);
            var facade = new BusinessContentService(store, new AllowAllActionExecutionPolicyService(), unexpectedScopes);
            assertThat(facade.history(unscoped, row.getId(), "title", HISTORY)).hasSize(1);
            assertThat(facade.history(unscoped, row.getId(), "title", HISTORY).getFirst().sourceLanguage()).isEqualTo("en");
            org.mockito.Mockito.verifyNoInteractions(unexpectedScopes);
        }
    }
    @Test void rollbackRestoresConsumedIntentForRetryWithoutOverwritingLaterCallerIntent() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("回滚后重试");
            var original = Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null));
            row.contentLanguageInputs(original);
            tx.executeWithoutResult(status -> {
                service.insert(row);
                assertThat(row.contentLanguageInputs()).isEmpty();
                row.setQuantity(2); service.update(row);
                status.setRollbackOnly();
            });
            assertThat(service.select(row.getId())).isNull();
            assertThat(row.contentLanguageInputs()).isEqualTo(original);
            service.insert(row);
            assertThat(history(row).getFirst().sourceLanguage()).isEqualTo("zh-CN");
            assertThat(row.contentLanguageInputs()).isEmpty();

            var retry = service.select(row.getId()); retry.setTitle("Updated text");
            retry.contentLanguageInputs(Map.of("title", new ContentWriteContext.SourceInput("en", 1)));
            tx.executeWithoutResult(status -> {
                service.update(retry);
                retry.contentLanguageInputs(Map.of()); // Explicitly abandon the input after save.
                status.setRollbackOnly();
            });
            assertThat(retry.contentLanguageInputs()).isEmpty();
            assertThat(history(row)).hasSize(1);

            var changed = service.select(row.getId()); changed.setTitle("Deutsch");
            changed.contentLanguageInputs(Map.of("title", new ContentWriteContext.SourceInput("de", 1)));
            var later = Map.of("title", new ContentWriteContext.SourceInput("de-DE", 1));
            tx.executeWithoutResult(status -> {
                service.update(changed);
                changed.contentLanguageInputs(later);
                status.setRollbackOnly();
            });
            assertThat(changed.contentLanguageInputs()).isEqualTo(later);
        }
    }
    @Test void existingUntrackedTextRetainsUnknownProvenanceAndItsRealBeforeImage() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            PlatformAbilityRuntime.configureEntitySaveLifecycleListener(EntitySaveLifecycleListener.NONE);
            var untouched = row("存量原文"); service.insert(untouched);
            var edited = row("修改前的存量原文"); service.insert(edited);
            PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener);
            var untracked = content.sources(service, untouched.getId()).fields().get("title");
            assertThat(untracked.text()).isEqualTo("存量原文");
            assertThat(untracked.expectedRevision()).isNull();
            assertThat(untracked.source()).isNull();
            untouched.setQuantity(10); service.update(untouched);
            var baseline = history(untouched).getFirst();
            assertThat(baseline.revisionKind()).isEqualTo("UNTRACKED_BASELINE");
            assertThat(baseline.authorId()).isNull();
            assertThat(baseline.authoredAt()).isNull();
            assertThat(baseline.revisionAuthorId()).isEqualTo("cn");
            assertThat(content.sources(service, untouched.getId()).fields().get("title").source()).isEqualTo(baseline);
            try (var correction = input(untouched, "zh-CN", 1)) { service.update(untouched); }
            assertThat(history(untouched).getFirst().authorId()).isNull();
            assertThat(history(untouched).getFirst().revisionKind()).isEqualTo("SOURCE_LANGUAGE_CORRECTION");
            PlatformAbilityRuntime.configureEntitySaveLifecycleListener(EntitySaveLifecycleListener.NONE);
            untouched.setTitle("能力停用期间的未追踪修改"); service.update(untouched);
            PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener);
            var gap = content.sources(service, untouched.getId()).fields().get("title");
            assertThat(gap.expectedRevision()).isEqualTo(2);
            assertThat(gap.source()).isNull();
            assertThat(gap.text()).isEqualTo("能力停用期间的未追踪修改");
            untouched.setQuantity(11); service.update(untouched);
            assertThat(history(untouched).getFirst().revisionKind()).isEqualTo("UNTRACKED_BASELINE");
            assertThat(history(untouched).getFirst().revision()).isEqualTo(3);
            assertThat(history(untouched).getFirst().authorId()).isNull();
            try (var american = actor("us")) {
                edited.setTitle("Actual English business edit");
                edited.contentLanguageInputs(Map.of("title", new ContentWriteContext.SourceInput("en", null)));
                service.update(edited);
            }
            var versions = history(edited);
            assertThat(versions).extracting(BusinessContentService.SourceRevision::text)
                    .containsExactly("Actual English business edit", "修改前的存量原文");
            assertThat(versions.getFirst().authorId()).isEqualTo("us");
            assertThat(versions.getFirst().sourceLanguage()).isEqualTo("en");
            assertThat(versions.get(1).authorId()).isNull();
            assertThat(versions.get(1).revisionKind()).isEqualTo("UNTRACKED_BASELINE");
            var current = content.sources(service, edited.getId());
            assertThat(current.recordVersion()).isEqualTo(service.select(edited.getId()).getVersion());
            assertThat(current.fields().get("title").expectedRevision()).isEqualTo(2);
            assertThat(current.fields().get("title").content().key()).isEqualTo("category-name");
            try (var other = TenantContext.use("tenant-b")) {
                assertThatThrownBy(() -> content.sources(service, edited.getId()))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
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
            var parent = new DynamicRecord(main).setValue("description", "80°C，暂勿发货").setValue("quantity", 7);
            var child = new DynamicRecord(line).setValue("description", "Investigation pending");
            parent.setChildren("lines", List.of(child));
            parent.contentLanguageInputs(Map.of("description", new ContentWriteContext.SourceInput("zh-CN", null)));
            child.contentLanguageInputs(Map.of("description", new ContentWriteContext.SourceInput("en-US", null)));
            tx.executeWithoutResult(status -> {
                records.create(module.moduleAlias(), "main", parent);
                assertThat(parent.contentLanguageInputs()).isEmpty();
                assertThat(child.contentLanguageInputs()).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(parent.contentLanguageInputs().get("description").languageTag()).isEqualTo("zh-CN");
            assertThat(child.contentLanguageInputs().get("description").languageTag()).isEqualTo("en-US");
            records.create(module.moduleAlias(), "main", parent);
            var parentHistory = content.history(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "description", HISTORY);
            var childHistory = content.history(runtime.entityService(module.moduleAlias(), "line"), child.getId(), "description", HISTORY);
            assertThat(content.history(records.mainEntity(module.moduleAlias()), parent.getId(), "description", HISTORY)).isEqualTo(parentHistory);
            DataScopeCriteriaService viewWithoutQuery = new AllowAllDataScopeCriteriaService() {
                @Override public DataScopeCriteriaResult resolveReadScope(String alias, ActionExecutionPolicy action,
                        Criteria criteria, Optional<CurrentUser> actor) {
                    return PlatformAction.QUERY.code().equals(action.actionCode())
                            ? DataScopeCriteriaResult.restricted(criteria.eq("id", "not-query-visible"))
                            : DataScopeCriteriaResult.unrestricted(criteria);
                }
            };
            var viewRecords = new DynamicRecordService(runtime, new AllowAllActionExecutionPolicyService(), viewWithoutQuery);
            var viewContent = new BusinessContentService(store, new AllowAllActionExecutionPolicyService(), viewWithoutQuery);
            assertThat(viewRecords.mainEntity(module.moduleAlias()).list(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            assertThat(viewContent.sources(viewRecords.mainEntity(module.moduleAlias()), parent.getId()).fields()).containsKey("description");
            assertThat(viewContent.history(viewRecords.mainEntity(module.moduleAlias()), parent.getId(), "description", HISTORY)).isEqualTo(parentHistory);
            DataScopeCriteriaService blockedView = new AllowAllDataScopeCriteriaService() {
                @Override public DataScopeCriteriaResult resolveReadScope(String alias, ActionExecutionPolicy action,
                        Criteria criteria, Optional<CurrentUser> actor) {
                    return DataScopeCriteriaResult.restricted(criteria.eq("id", "not-view-visible"));
                }
            };
            var invisibleRecords = new DynamicRecordService(runtime, new AllowAllActionExecutionPolicyService(), blockedView);
            assertThatThrownBy(() -> content.sources(invisibleRecords.mainEntity(module.moduleAlias()), parent.getId()))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            var sourceState = content.sources(records.mainEntity(module.moduleAlias()), parent.getId());
            assertThat(sourceState.fields().get("description").source()).isEqualTo(parentHistory.getFirst());
            assertThat(sourceState.fields().get("description").text()).isEqualTo("80°C，暂勿发货");
            assertThat(parentHistory.getFirst().sourceLanguage()).isEqualTo("zh-CN");
            assertThat(childHistory.getFirst().sourceLanguage()).isEqualTo("en-US");
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("records", records);
            DataScopeCriteriaService parentOnlyScope = new AllowAllDataScopeCriteriaService() {
                @Override public DataScopeCriteriaResult resolveReadScope(String alias, ActionExecutionPolicy action,
                        Criteria criteria, Optional<CurrentUser> actor) {
                    return DataScopeCriteriaResult.unrestricted(criteria.eq("quantity", 7));
                }
            };
            var aggregateContent = new BusinessContentService(store, new AllowAllActionExecutionPolicyService(),
                    parentOnlyScope, beans.getBeanProvider(DynamicRecordService.class));
            var childSource = aggregateContent.childSources(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", child.getId());
            assertThat(childSource.fields().get("description").source()).isEqualTo(childHistory.getFirst());
            assertThat(aggregateContent.childHistory(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", child.getId(), "description", HISTORY))
                    .isEqualTo(childHistory);
            assertThat(content.childSources(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "lines", child.getId()))
                    .isEqualTo(childSource);
            var another = new DynamicRecord(main).setValue("description", "Other parent").setValue("quantity", 7);
            var otherLine = new DynamicRecord(line).setValue("description", "Other parent's text");
            another.setChildren("lines", List.of(otherLine)); records.create(module.moduleAlias(), "main", another);
            assertThatThrownBy(() -> aggregateContent.childSources(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", otherLine.getId()))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            try (var otherTenant = TenantContext.use("tenant-b")) {
                assertThatThrownBy(() -> aggregateContent.childSources(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", child.getId()))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
            assertThat(parentHistory.getFirst().sourceKey()).isNotEqualTo(childHistory.getFirst().sourceKey());
            DataScopeCriteriaService restricted = new AllowAllDataScopeCriteriaService() {
                @Override public DataScopeCriteriaResult resolveReadScope(String alias, ActionExecutionPolicy action,
                        Criteria criteria, Optional<CurrentUser> actor) {
                    return DataScopeCriteriaResult.unrestricted(criteria.eq("id", "not-visible"));
                }
            };
            var restrictedFacade = new BusinessContentService(store, new AllowAllActionExecutionPolicyService(), restricted);
            assertThatThrownBy(() -> restrictedFacade.childSources(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "lines", child.getId()))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> restrictedFacade.history(runtime.entityService(module.moduleAlias(), "main"),
                    parent.getId(), "description", HISTORY)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> restrictedFacade.history(records.mainEntity(module.moduleAlias()),
                    parent.getId(), "description", HISTORY)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            var reopened = records.select(module.moduleAlias(), "main", parent.getId());
            assertThat(reopened.getChildren("lines").getFirst().getValue("description")).isEqualTo("Investigation pending");
            var partial = new DynamicRecord(main).setValue("quantity", 0);
            partial.setId(parent.getId()); partial.setVersion(reopened.getVersion());
            records.update(module.moduleAlias(), "main", partial);
            assertThat(content.history(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "description", HISTORY)).hasSize(1);
            assertThat(records.select(module.moduleAlias(), "main", parent.getId()).getValue("description")).isEqualTo("80°C，暂勿发货");
        }
    }
    @Test void staleDynamicInputCannotRetainWithdrawnContentOrBypassNewMaskingPolicy() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String alias = "test.content_stale_" + suffix;
        var old = new EntityDefinition("main", "content_stale_" + suffix, "Content", List.of(
                FieldDefinition.text("description", "Description").content(new ContentFieldDefinition("description-stable", ContentPolicy.AUTO_TRANSLATE))));
        new DynamicSchemaService(operations).ensureTable(old);
        try (var runtime = new DynamicRecordRuntime(operations).register(new ModuleDefinition(alias, "Content", List.of(old)));
             var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var incoming = new DynamicRecord(old).setValue("description", "Originally public");
            runtime.entityService(alias, "main").insert(incoming);
            var source = new BusinessContentSource("tenant-a", alias, "main", incoming.getId(), "description-stable");
            assertThat(store.latest(source).getRevision()).isEqualTo(1);
            var masked = FieldDefinition.text("description", "Description").protection(new net.ximatai.muyun.spring.common.security.FieldProtectionDefinition(
                    null, null, net.ximatai.muyun.spring.common.security.FieldMaskingPolicy.FULL));
            var current = new EntityDefinition("main", old.tableName(), "Content", List.of(masked));
            runtime.refresh(new ModuleDefinition(alias, "Content", List.of(current)));
            incoming.setValue("description", "Must never enter unprotected content history");
            runtime.entityService(alias, "main").update(incoming);
            assertThat(store.history(source, HISTORY)).hasSize(1);
            assertThat(store.latest(source).getOriginalText()).isEqualTo("Originally public");
            assertThatThrownBy(() -> content.history(runtime.entityService(alias, "main"), incoming.getId(), "description", HISTORY))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            try (var input = ContentWriteContext.open(incoming, Map.of("description", new ContentWriteContext.SourceInput("en", 1)))) {
                assertThatThrownBy(() -> runtime.entityService(alias, "main").update(incoming))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("undeclared content field");
            }
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
    @Import({BusinessContentStore.class, BusinessContentService.class, BusinessContentSaveLifecycleListener.class, BusinessContentDeletionObserver.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ActionExecutionPolicyService policies() { return new AllowAllActionExecutionPolicyService(); }
        @Bean DataScopeCriteriaService scopes() { return new AllowAllDataScopeCriteriaService(); }
    }
}
