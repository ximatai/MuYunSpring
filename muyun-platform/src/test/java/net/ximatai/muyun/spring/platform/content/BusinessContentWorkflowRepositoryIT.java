package net.ximatai.muyun.spring.platform.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.ai.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real fact/source/translation/task and frozen-history repositories; permission decisions stay live at each boundary. */
@SpringBootTest(classes = {BusinessContentRepositoryIT.TestApplication.class,
        BusinessContentWorkflowRepositoryIT.WorkflowRepositories.class, BusinessContentFacts.class})
class BusinessContentWorkflowRepositoryIT extends PlatformPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-17T01:00:00Z");
    private static final String RAW = "检查完成，继续暂停发货。";
    private static final String DE = "Prüfung abgeschlossen; Versand bleibt angehalten.";
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowHistoryInstanceDao histories;
    @Autowired WorkflowEventDao events;
    @Autowired WorkflowNodeInstanceDao nodes;
    @Autowired WorkflowRouteInstanceDao routes;
    @Autowired WorkflowTaskDao tasks;
    @Autowired BusinessContentFacts facts;
    @Autowired ContentGenerationFixtureDao contentRows;
    @Autowired BusinessContentSaveLifecycleListener contentListener;
    @Autowired BusinessContentService contentSources;
    @Autowired net.ximatai.muyun.database.core.IDatabaseOperations<?> operations;
    @Autowired BusinessContentStore originals;
    @Autowired BusinessContentTranslationStore translations;
    @Autowired BusinessContentTranslationService maintenance;
    @Autowired BusinessContentGenerationStore jobs;
    @Autowired BusinessContentGenerationDao jobDao;
    @Autowired ObjectProvider<net.ximatai.muyun.spring.platform.job.JobService> queue;
    @Autowired BusinessContentJobTestConfiguration jobConfiguration;
    @Autowired BusinessContentRevisionDao originalDao;
    @Autowired BusinessContentTranslationDao translationDao;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    private TransactionTemplate tx;
    private AiModelGateway gateway;
    private WorkflowOpinionAccess access;
    private WorkflowOpinionContentScope scope;
    private WorkflowOpinionContentService opinions;
    private WorkflowArchiveService archives;
    private WorkflowHistoryQueryService historyQueries;
    private WorkflowEventWriter writer;
    private BusinessContentGenerationService engine;
    private ObjectProvider<TrustedExecutionIdentityResolver> identities;
    private ObjectProvider<BusinessContentGenerationScope> scopes;
    private final Map<String, CurrentUser> users = new HashMap<>();
    private final AtomicBoolean model = new AtomicBoolean(), visible = new AtomicBoolean();
    private WorkflowInstance instance;
    private WorkflowEvent event;
    private String module;

    @BeforeEach @SuppressWarnings("unchecked") void install() {
        model.set(false); visible.set(true); tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(contentListener);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return tx.execute(status -> work.get()); }
            public void lock(String namespace, String key) {
                new JdbcTemplate(dataSource).queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                        Object.class, namespace + key);
            }
        });
        var policies = mock(WorkflowActionPolicyService.class);
        doAnswer(call -> { if (!visible.get()) throw new PlatformAccessDeniedException("scope revoked"); return null; })
                .when(policies).requireRecordView(any());
        archives = new WorkflowArchiveService(instances, nodes, routes, tasks, events, histories);
        access = new WorkflowOpinionAccess(instances, histories, events, archives, policies, facts);
        scope = new WorkflowOpinionContentScope(access, new ObjectMapper().findAndRegisterModules());
        gateway = mock(AiModelGateway.class); when(gateway.isTextGenerationAvailable()).thenAnswer(call -> model.get());
        when(gateway.generate(any())).thenAnswer(call -> {
            assertThat(CurrentUserContext.currentUser().orElseThrow().system()).isFalse();
            assertThat(TenantContext.currentTenantId()).contains("opinion-a");
            return new AiTextResponse("{\"text\":\"" + DE + "\"}", "stop", "provider-opinion");
        });
        identities = mock(ObjectProvider.class); scopes = mock(ObjectProvider.class);
        when(scopes.orderedStream()).thenAnswer(call -> Stream.of(scope));
        when(identities.getIfAvailable()).thenReturn(new TrustedExecutionIdentityResolver() {
            public Optional<Reference> capture(String alias, String action) {
                var user = CurrentUserContext.currentUser().orElseThrow(); users.put(user.userId(), user);
                return Optional.of(new Reference(user.userId(), user.tenantId(), NOW, null));
            }
            public Optional<Identity> resolve(Reference reference, String alias, String action) {
                return Optional.ofNullable(users.get(reference.userId())).map(user -> new Identity(user, null));
            }
        });
        engine = restart();
        ObjectProvider<BusinessContentSubmissionScope> submissions = mock(ObjectProvider.class);
        when(submissions.orderedStream()).thenAnswer(call -> Stream.empty());
        var recorder = new WorkflowOpinionRecorder(instances, events, facts, scope,
                new BusinessContentSubmissionService(gateway, engine, submissions, transactions, "zh-CN,en,de"));
        writer = new WorkflowEventWriter(events, fact -> recorder.persisted((WorkflowEventWriter.Persisted) fact));
        opinions = new WorkflowOpinionContentService(access, facts, maintenance, engine, scope);
        ObjectProvider<WorkflowUserTitleResolver> titles = mock(ObjectProvider.class);
        when(titles.getIfAvailable(any())).thenReturn(WorkflowUserTitleResolver.NONE);
        historyQueries = new WorkflowHistoryQueryService(histories, archives, policies, titles, facts);
        module = "test.workflow_opinion_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (var actor = actor("cn"); var tenant = TenantContext.use("opinion-a")) {
            tx.executeWithoutResult(status -> {
                instance = new WorkflowInstance(); instance.setDefinitionId("definition"); instance.setWorkflowVersionId("version");
                instance.setVersionNo(1); instance.setApprovalEnabled(false); instance.setModuleAlias(module); instance.setRecordId("business-record");
                instance.setStartedBy("cn"); instance.setStartedAt(NOW); instance.setSnapshotText("{}");
                instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING); EntityLifecycle.prepareInsert(instance, NOW); instances.insert(instance);
                instance = instances.findById(instance.getId());
            });
            event = WorkflowOpinionInput.withLanguage("zh-CN", () -> tx.execute(status -> {
                var opinion = new WorkflowRuntimeEventFactory().taskCompleted(instance, new WorkflowTask(), "complete", "cn", RAW, NOW);
                EntityLifecycle.prepareInsert(opinion, NOW); writer.insert(opinion); return opinion;
            }));
        }
    }
    @AfterEach void close() {
        PlatformAbilityRuntime.resetMutationTransactionOperator(); PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
        CurrentUserContext.clear(); TenantContext.clear();
    }

    @Test void originalsManualMaintenanceAndPrivateDraftsRemainAvailableWithoutAModelAcrossArchival() {
        try (var actor = actor("de"); var tenant = TenantContext.use("opinion-a")) {
            var source = opinions.sources(instance.getId(), event.getId()).fields().get("reason").source();
            assertThat(source.text()).isEqualTo(RAW); assertThat(source.sourceLanguage()).isEqualTo("zh-CN");
            assertThat(source.authorId()).isEqualTo("cn"); assertThat(source.authoredAt()).isEqualTo(NOW);
            assertThat(opinions.generation(instance.getId(), event.getId(), "de", true, false).state()).isEqualTo("UNAVAILABLE");
            assertThat(jobCount()).isZero();
            var prepared = opinions.prepare(instance.getId(), event.getId(), "de", PlatformAction.CONTENT_EDIT);
            var draft = opinions.draft(instance.getId(), event.getId(), "de", change(prepared, DE, null));
            assertThat(opinions.read(instance.getId(), event.getId(), "de").fields().get("reason").text()).isEqualTo(RAW);
            opinions.confirm(instance.getId(), event.getId(), "de", change(draft, null, draft.draft().revision()));
            archive();
            assertThat(opinions.read(instance.getId(), event.getId(), "de").fields().get("reason").text()).isEqualTo(DE);
            assertThat(opinions.history(instance.getId(), event.getId(), PageRequest.of(1, 20))).containsExactly(source);
            var frozen = archives.parseSnapshot(histories.findById(instance.getId())).events().getFirst();
            assertThat(frozen.getReason()).isEqualTo(RAW); assertThat(frozen.getOperatorId()).isEqualTo("cn");
            assertThat(WorkflowHistoryEventView.from(frozen, null).version()).isEqualTo(frozen.getVersion());
            verify(gateway, never()).generate(any());
        }
    }

    @Test void pendingWorkSurvivesArchivalAndRestartWithTheOriginalReaderIdentity() {
        try (var actor = actor("de"); var tenant = TenantContext.use("opinion-a")) {
            model.set(true); assertThat(jobCount()).isZero();
            var queued = opinions.generation(instance.getId(), event.getId(), "de", true, false);
            assertThat(queued.state()).isEqualTo("PENDING");
            var before = opinions.sources(instance.getId(), event.getId()); archive();
            when(gateway.generate(any())).thenAnswer(call -> {
                assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("de");
                assertThat(TenantContext.currentTenantId()).contains("opinion-a");
                return new AiTextResponse("{\"text\":\"" + DE + "\"}", "stop", "provider-opinion");
            });
            assertThat(restart().runOne()).isTrue();
            assertThat(jobDao.findById(queued.reference()).getStatus()).isEqualTo("SUCCEEDED");
            assertThat(opinions.read(instance.getId(), event.getId(), "de").fields().get("reason").text()).isEqualTo(DE);
            assertThat(opinions.sources(instance.getId(), event.getId())).isEqualTo(before);
        }
    }

    @Test void aRevokedRecordScopeOrAWithdrawnReaderCannotInvokeTheModel() {
        try (var actor = actor("de"); var tenant = TenantContext.use("opinion-a")) {
            model.set(true);
            var queued = opinions.generation(instance.getId(), event.getId(), "de", true, false);
            visible.set(false);
            assertThatThrownBy(() -> opinions.read(instance.getId(), event.getId(), "de")).isInstanceOf(PlatformAccessDeniedException.class);
            assertThat(engine.runOne()).isTrue();
            assertThat(jobDao.findById(queued.reference()).getStatus()).isEqualTo("CANCELLED");
            visible.set(true);
            var retry = opinions.generation(instance.getId(), event.getId(), "de", true, true);
            users.remove("de");
            assertThat(engine.runOne()).isTrue();
            assertThat(jobDao.findById(retry.reference()).getStatus()).isEqualTo("CANCELLED");
            verify(gateway, never()).generate(any());
        }
        try (var actor = CurrentUserContext.use(CurrentUser.tenantUser("de", "de", "opinion-b")); var tenant = TenantContext.use("opinion-b")) {
            assertThatThrownBy(() -> opinions.sources(instance.getId(), event.getId())).isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test void deletingHistoryPurgesCopiesAndRejectsARealLateProviderResponse() {
        try (var actor = actor("de"); var tenant = TenantContext.use("opinion-a")) {
            model.set(true); opinions.generation(instance.getId(), event.getId(), "de", true, false); archive();
            when(gateway.generate(any())).thenAnswer(call -> {
                tx.executeWithoutResult(status -> historyQueries.deleteHistory(instance.getId()));
                return new AiTextResponse("{\"text\":\"" + DE + "\"}", "stop", "provider-opinion");
            });
            assertThat(engine.runOne()).isTrue();
            assertThat(originalDao.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 20))).isEmpty();
            assertThat(translationDao.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 20))).isEmpty();
            assertThat(jobCount()).isZero();
            assertThatThrownBy(() -> opinions.read(instance.getId(), event.getId(), "de")).isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test void aLegacyOpinionKeepsItsKnownActualAuthorAndUnknownLanguageWhenMaintenanceIsOpened() {
        try (var actor = actor("de"); var tenant = TenantContext.use("opinion-a")) {
            var legacy = new WorkflowRuntimeEventFactory().taskCompleted(instance, new WorkflowTask(), "complete", "legacy-author", "Legacy opinion", NOW);
            tx.executeWithoutResult(status -> { EntityLifecycle.prepareInsert(legacy, NOW); events.insert(legacy); });
            assertThat(opinions.sources(instance.getId(), legacy.getId()).fields().get("reason").source()).isNull();
            var original = tx.execute(status -> opinions.prepare(instance.getId(), legacy.getId(), "de", PlatformAction.CONTENT_EDIT)).original();
            assertThat(original.authorId()).isEqualTo("legacy-author"); assertThat(original.sourceLanguage()).isNull();
            assertThat(original.authoredAt()).isEqualTo(NOW);
            model.set(true);
            assertThat(opinions.generation(instance.getId(), legacy.getId(), "de", true, false).state()).isEqualTo("SOURCE_UNKNOWN");
            assertThat(jobCount()).isZero();
        }
    }
    @Test void taskFormCommandsPreserveOriginalLanguageAndRevisionThroughBothNormalWritePipelines() {
        var raw = "原始工厂说明"; var changed = "Retest the replacement seal.";
        var staticService = new FormService(module, contentRows);
        var staticRow = new ContentGenerationFixture(); staticRow.setDescription(raw);
        @SuppressWarnings("unchecked") ObjectProvider<CrudAbility<?>> abilities = mock(ObjectProvider.class);
        when(abilities.orderedStream()).thenAnswer(call -> Stream.of(staticService));
        var staticExecutor = new net.ximatai.muyun.spring.platform.module.DefaultModuleRecordActionExecutor(abilities,
                mock(net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService.class),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(), new ObjectMapper().findAndRegisterModules());
        var input = Map.of("description", new net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput("en", 1));
        try (var actor = actor("cn"); var tenant = TenantContext.use("opinion-a")) {
            try (var source = net.ximatai.muyun.spring.common.content.ContentWriteContext.open(staticRow,
                    Map.of("description", new net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput("zh-CN", null)))) {
                staticService.insert(staticRow);
            }
        }
        try (var actor = actor("us"); var tenant = TenantContext.use("opinion-a")) {
            staticExecutor.executeApprovalBusiness(new net.ximatai.muyun.spring.common.platform.ModuleRecordActionCommand(
                    module, staticRow.getId(), "update", staticRow.getVersion(), Map.of("description", changed), Map.of(), input));
            var history = contentSources.history(staticService, staticRow.getId(), "description", PageRequest.of(1, 20));
            assertThat(history).hasSize(2);
            assertThat(history.getFirst().sourceLanguage()).isEqualTo("en"); assertThat(history.getFirst().authorId()).isEqualTo("us");
            assertThat(history.getLast().text()).isEqualTo(raw); assertThat(history.getLast().sourceLanguage()).isEqualTo("zh-CN");
            var definition = new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition("main", "wf_form_" + UUID.randomUUID().toString().substring(0, 8), "Form",
                    List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.text("description", "Description")
                            .content(new net.ximatai.muyun.spring.common.content.ContentFieldDefinition("description", net.ximatai.muyun.spring.common.content.ContentPolicy.AUTO_TRANSLATE))));
            var declared = new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition(module, "Form", List.of(definition));
            new net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService(operations).ensureModule(declared);
            try (var runtime = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime(operations).register(declared)) {
                var records = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService(runtime);
                var row = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(definition).setValue("description", raw);
                row.contentLanguageInputs(Map.of("description", new net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput("zh-CN", null)));
                records.create(module, "main", row);
                when(abilities.orderedStream()).thenAnswer(call -> Stream.empty());
                var dynamicExecutor = new net.ximatai.muyun.spring.platform.module.DefaultModuleRecordActionExecutor(abilities, records,
                        new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(), new ObjectMapper().findAndRegisterModules());
                dynamicExecutor.executeApprovalBusiness(new net.ximatai.muyun.spring.common.platform.ModuleRecordActionCommand(
                        module, row.getId(), "update", row.getVersion(), Map.of("description", changed), Map.of(), input));
                var latest = contentSources.history(runtime.entityService(module, "main"), row.getId(), "description", PageRequest.of(1, 20));
                assertThat(latest).hasSize(2); assertThat(latest.getFirst().sourceLanguage()).isEqualTo("en");
                assertThat(latest.getFirst().authorId()).isEqualTo("us"); assertThat(latest.getLast().sourceLanguage()).isEqualTo("zh-CN");
            }
        }
    }
    private static class FormService extends AbstractAbilityService<ContentGenerationFixture> implements SoftDeleteAbility<ContentGenerationFixture> {
        FormService(String module, ContentGenerationFixtureDao dao) { super(module, ContentGenerationFixture.class, dao); }
    }

    private void archive() { tx.executeWithoutResult(status -> archives.archiveCurrentInstance(instance, WorkflowArchiveReason.RESET, NOW.plusSeconds(1))); }
    private long jobCount() { return jobDao.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 200)).size(); }
    private BusinessContentGenerationService restart() {
        var result = new BusinessContentGenerationService(originals, translations, jobs, identities, scopes, gateway,
                new ObjectMapper().findAndRegisterModules(), transactions, Clock.systemUTC(), queue);
        jobConfiguration.engine = result; return result;
    }
    private static BusinessContentTranslationService.Change change(BusinessContentTranslationService.Maintenance state, String text, Integer reviewed) {
        return new BusinessContentTranslationService.Change(state.original().revisionId(), state.original().revision(), state.expectedRevision(), text, reviewed);
    }
    private static CurrentUserContext.Scope actor(String id) { return CurrentUserContext.use(CurrentUser.tenantUser(id, id, "opinion-a")); }
    @Configuration(proxyBeanMethods = false)
    @EnableMuYunRepositories(basePackageClasses = WorkflowEventDao.class)
    static class WorkflowRepositories { }
}
