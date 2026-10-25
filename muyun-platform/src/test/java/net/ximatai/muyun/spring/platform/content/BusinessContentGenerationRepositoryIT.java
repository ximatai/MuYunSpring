package net.ximatai.muyun.spring.platform.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.exception.*;
import net.ximatai.muyun.spring.platform.ai.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {BusinessContentRepositoryIT.TestApplication.class, BusinessContentGenerationStore.class})
class BusinessContentGenerationRepositoryIT extends PlatformPostgresIntegrationTest {
    private static final String MODULE = "test.content_generation";
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final String RAW = "M-100 在 80°C 下抽检 12 件，2 件不合格。";
    private static final String EN = "M-100: 12 parts tested at 80°C; 2 failed.";
    private static final BusinessContentGenerationScope.Reference SCOPE = new BusinessContentGenerationScope.Reference("fixture", "version-1", "{}");
    @Autowired ContentGenerationFixtureDao dao;
    @Autowired BusinessContentGenerationDao jobDao;
    @Autowired ObjectProvider<net.ximatai.muyun.spring.platform.job.JobService> queue;
    @Autowired BusinessContentJobTestConfiguration jobConfiguration;
    @Autowired BusinessContentGenerationStore jobs;
    @Autowired BusinessContentStore originals;
    @Autowired BusinessContentTranslationStore translations;
    @Autowired BusinessContentTranslationDao translationDao;
    @Autowired BusinessContentService content;
    @Autowired BusinessContentTranslationService maintenance;
    @Autowired BusinessContentSaveLifecycleListener listener;
    @Autowired BusinessContentDeletionObserver deletion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final AtomicBoolean identityActive = new AtomicBoolean(true);
    private final AtomicBoolean scopeActive = new AtomicBoolean(true);
    private final Map<String, CurrentUser> users = new ConcurrentHashMap<>();
    private Consumer<BusinessContentService.Location> scopeGuard;
    private BiConsumer<String, String> beforeLock;
    private AiModelGateway gateway;
    private FixtureService service;
    private TransactionTemplate tx;
    private BusinessContentGenerationService engine;
    private ObjectProvider<TrustedExecutionIdentityResolver> identityProvider;
    private ObjectProvider<BusinessContentGenerationScope> scopeProvider;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @BeforeEach @SuppressWarnings("unchecked") void install() {
        now.set(NOW); identityActive.set(true); scopeActive.set(true);
        scopeGuard = location -> { }; beforeLock = (scope, key) -> { };
        gateway = mock(AiModelGateway.class); when(gateway.isTextGenerationAvailable()).thenReturn(true);
        when(gateway.generate(any())).thenReturn(response(EN));
        service = new FixtureService(dao); tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return tx.execute(status -> work.get()); }
            public void lock(String scope, String key) {
                beforeLock.accept(scope, key);
                new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                        "select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new CompositeEntitySaveLifecycleListener(List.of(listener)));
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new net.ximatai.muyun.spring.ability.deletion.CompositeDeletionLifecycleListener(
                net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener.NONE, List.of(deletion)));
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        identityProvider = mock(ObjectProvider.class); scopeProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(new TrustedExecutionIdentityResolver() {
            public Optional<Reference> capture(String module, String action) {
                var user = CurrentUserContext.currentUser().orElseThrow(); users.put(user.userId(), user);
                return Optional.of(new Reference(user.userId(), user.tenantId(), NOW.minusSeconds(1), null));
            }
            public Optional<Identity> resolve(Reference reference, String module, String action) {
                return identityActive.get() ? Optional.of(new Identity(users.get(reference.userId()), null)) : Optional.empty();
            }
        });
        var adapter = new BusinessContentGenerationScope() {
            public String key() { return "fixture"; }
            public <T> T withAccess(Reference reference, String module, BusinessContentService.Location location,
                                    String field, Function<BusinessContentService.FieldTarget, T> work) {
                if (!scopeActive.get()) throw new PlatformAccessDeniedException("scope withdrawn");
                assertThat(CurrentUserContext.currentUser()).isPresent();
                scopeGuard.accept(location);
                try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPlatformAction(
                        module, PlatformAction.VIEW, Set.of(location.recordId()), CurrentUserContext.currentUser()))) {
                    return work.apply(content.target(service, location, field, PlatformAction.VIEW));
                }
            }
        };
        when(scopeProvider.orderedStream()).thenAnswer(invocation -> Stream.of(adapter));
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("delete from platform_job_run");
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("delete from platform_job_plan");
        engine = restarted();
        jobDao.query(Criteria.of(), PageRequest.of(1, 10_000)).forEach(row -> jobDao.deleteById(row.getId()));
    }
    @AfterEach void reset() {
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener(); PlatformAbilityRuntime.resetDeletionLifecycleListener();
        PlatformAbilityRuntime.resetMutationTransactionOperator(); PlatformAbilityRuntime.resetDataScopeCriteriaService();
        CurrentUserContext.clear(); ActingContextHolder.clear(); ActionExecutionContextHolder.clear(); TenantContext.clear();
    }

    @Test void manualFieldsGenerateOnlyAfterExplicitRequestAndUseTheSameDurableWorker() {
        installSubmissions("zh-CN,en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = new ContentGenerationFixture(); row.setManualName(RAW);
            try (var input = ContentWriteContext.open(row, Map.of("manualName", new ContentWriteContext.SourceInput("zh-CN",null)))) {
                service.insert(row);
            }
            assertThat(jobDao.query(Criteria.of().eq("recordId",row.getId()),PageRequest.of(1,10))).isEmpty();
            when(gateway.isTextGenerationAvailable()).thenReturn(false);
            assertThat(engine.request(SCOPE,MODULE,location(row),"manualName","en",false).state()).isEqualTo("UNAVAILABLE");
            assertThat(jobDao.query(Criteria.of().eq("recordId",row.getId()),PageRequest.of(1,10))).isEmpty();
            when(gateway.isTextGenerationAvailable()).thenReturn(true);
            assertThat(engine.request(SCOPE,MODULE,location(row),"manualName","en",false).state()).isEqualTo("PENDING");
            assertThat(restarted().runOne()).isTrue();
            assertThat(maintenance.read(service,location(row),"en").fields().get("manualName").text()).isEqualTo(EN);
            service.update(service.select(row.getId()));
            assertThat(jobDao.query(Criteria.of().eq("recordId",row.getId()),PageRequest.of(1,10))).hasSize(1);
        }
    }

    @Test void missingModelCreatesNoExecutableWorkAndManualMaintenanceRemainsUsable() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); when(gateway.isTextGenerationAvailable()).thenReturn(false);
            assertThat(request(row, false).state()).isEqualTo("UNAVAILABLE");
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            var prepared = maintenance.prepare(service, location(row), "description", "en", PlatformAction.CONTENT_EDIT);
            maintenance.saveDraft(service, location(row), "description", "en", new BusinessContentTranslationService.Change(
                    prepared.original().revisionId(), prepared.original().revision(), null, EN, null));
            maintenance.confirm(service, location(row), "description", "en", new BusinessContentTranslationService.Change(
                    prepared.original().revisionId(), prepared.original().revision(), 1, null, 1));
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").text()).isEqualTo(EN);
            assertThat(request(row, false).state()).isEqualTo("READY");
            verify(gateway, never()).generate(any());
        }
    }

    @Test void originalSubmissionAtomicallyRegistersPrimaryLanguagesAndRestartedWorkerUsesTheAuthor() {
        installSubmissions("zh-CN,en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN");
            var queued = jobDao.query(Criteria.of().eq("recordId", row.getId()), PageRequest.of(1, 10));
            assertThat(queued).hasSize(2).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo("PENDING");
                assertThat(job.getRequestJson()).contains("\"userId\":\"cn\"").doesNotContain(RAW);
            });
            verify(gateway, never()).generate(any());
            when(gateway.generate(any())).thenAnswer(invocation -> {
                assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("cn");
                assertThat(TransactionScopeSupport.isTransactionActive()).isFalse(); return response(EN);
            });
            assertThat(restarted().runOne()).isTrue();
            assertThat(restarted().runOne()).isTrue();
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").text()).isEqualTo(EN);
            int count = jobDao.query(Criteria.of(), PageRequest.of(1, 100)).size();
            service.update(service.select(row.getId()));
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 100))).hasSize(count);
        }
    }

    @Test void submissionFollowsOriginalRollbackAndCoalescesMultipleWritesOfTheSameField() {
        installSubmissions("en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var rolledBack = tx.execute(status -> { var row = insert(RAW, "zh-CN"); status.setRollbackOnly(); return row.getId(); });
            assertThat(dao.findById(rolledBack)).isNull();
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            var row = tx.execute(status -> {
                var saved = insert(RAW, "zh-CN"); var next = service.select(saved.getId()); next.setDescription(RAW + " 请暂停处理。");
                try (var input = input(next, "zh-CN", 1)) { service.update(next); } return next;
            });
            var current = originals.latest(source(row));
            assertThat(current.getRevision()).isEqualTo(2);
            assertThat(jobDao.query(Criteria.of().eq("recordId", row.getId()), PageRequest.of(1, 10)))
                    .hasSize(2).allSatisfy(job -> assertThat(job.getRequestJson()).contains(current.getId()));
        }
    }

    @Test void unavailableModelsUnknownLanguagesAndWithdrawnSubmissionScopesLeaveOriginalSavesUsable() {
        installSubmissions("en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            when(gateway.isTextGenerationAvailable()).thenReturn(false);
            var noModel = insert(RAW, "zh-CN");
            when(gateway.isTextGenerationAvailable()).thenReturn(true);
            var unknown = insert(RAW, null);
            scopeActive.set(false); var denied = insert(RAW, "zh-CN");
            assertThat(service.select(noModel.getId())).isNotNull();
            assertThat(service.select(unknown.getId())).isNotNull();
            assertThat(service.select(denied.getId())).isNotNull();
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            scopeActive.set(true);
            // Installing a connection later is not a request to translate existing records.
            assertThat(engine.runOne()).isFalse(); verify(gateway, never()).generate(any());
        }
    }

    @Test void queueDatabaseFailureRollsBackItsSavepointWithoutLosingTheOriginalOrLeavingPartialJobs() {
        installSubmissions("en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var calls = new AtomicInteger();
            scopeGuard = location -> {
                if (calls.incrementAndGet() == 3) new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                        .queryForObject("select 1 / 0", Integer.class);
            };
            var row = insert(RAW, "zh-CN");
            assertThat(service.select(row.getId()).getDescription()).isEqualTo(RAW);
            assertThat(originals.latest(source(row)).getOriginalText()).isEqualTo(RAW);
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            verify(gateway, never()).generate(any());
        }
    }

    @Test void availabilityDatabaseFailureIsDeferredAndCannotAbortTheOriginalTransaction() {
        installSubmissions("en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            when(gateway.isTextGenerationAvailable()).thenAnswer(invocation ->
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("select 1 / 0", Boolean.class));
            var row = tx.execute(status -> {
                var saved = insert(RAW, "zh-CN");
                verify(gateway, never()).isTextGenerationAvailable();
                return saved;
            });
            assertThat(service.select(row.getId()).getDescription()).isEqualTo(RAW);
            assertThat(originals.latest(source(row)).getOriginalText()).isEqualTo(RAW);
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
        }
    }

    @Test void unavailableDecisionAfterASwallowedDatabaseErrorRollsBackAllQueueWorkAndPreservesTheOriginal() {
        installSubmissions("en,de");
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            for (int failAt : List.of(1, 3)) {
                var calls = new AtomicInteger();
                when(gateway.isTextGenerationAvailable()).thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == failAt) {
                        try { new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("select 1 / 0", Integer.class); }
                        catch (org.springframework.dao.DataAccessException unavailable) { return false; }
                    }
                    return true;
                });
                var row = insert(RAW, "zh-CN");
                assertThat(service.select(row.getId()).getDescription()).isEqualTo(RAW);
                assertThat(originals.latest(source(row)).getOriginalText()).isEqualTo(RAW);
                assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            }
        }
    }

    @Test void revokedProducerFieldReadPolicyHidesSourcesHistoryAndTranslationsAndCancelsQueuedModelWork() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            service.descriptionReadable = false;
            assertThat(content.sources(service, row.getId()).fields()).isEmpty();
            assertThatThrownBy(() -> content.history(service, row.getId(), "description", PageRequest.of(1, 10)))
                    .isInstanceOf(PlatformAccessDeniedException.class);
            assertThat(maintenance.read(service, location(row), "en").fields()).isEmpty();
            assertThatThrownBy(() -> maintenance.prepare(service, location(row), "description", "en", PlatformAction.CONTENT_EDIT))
                    .isInstanceOf(PlatformAccessDeniedException.class);
            assertThat(engine.runOne()).isTrue();
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("CANCELLED");
            verify(gateway, never()).generate(any());
        }
    }

    @SuppressWarnings("unchecked")
    private void installSubmissions(String languages) {
        ObjectProvider<BusinessContentSubmissionScope> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenAnswer(invocation -> Stream.of((BusinessContentSubmissionScope) change ->
                Optional.of(new BusinessContentSubmissionScope.Submission(SCOPE, MODULE,
                        BusinessContentService.Location.record(change.record().getId()), change.field(), change.originalRevisionId()))));
        var submissions = new BusinessContentSubmissionService(gateway, engine, adapters, transactions, languages);
        var sourceListener = new BusinessContentSaveLifecycleListener(originals,
                event -> submissions.originalChanged((BusinessContentOriginalChanged) event));
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new CompositeEntitySaveLifecycleListener(List.of(sourceListener)));
    }

    @Test void unknownSourceLanguageAndAlreadyMatchingOriginalDoNotCreateModelWork() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var unknown = insert(RAW, null);
            assertThat(request(unknown, false).state()).isEqualTo("SOURCE_UNKNOWN");
            assertThatThrownBy(() -> insert(RAW, "und")).isInstanceOf(IllegalArgumentException.class);
            var english = insert(EN, "en-US");
            assertThat(request(english, false).state()).isEqualTo("ORIGINAL");
            assertThat(jobDao.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty();
            verify(gateway, never()).generate(any());
        }
    }

    @Test void committedWorkSurvivesServiceRestartAndUsesRequestingReaderOutsideAnyTransaction() {
        try (var author = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); int businessVersion = row.getVersion();
            BusinessContentGenerationService.Status pending;
            try (var reader = actor("us")) { pending = request(row, false); }
            assertThat(request(row, false).reference()).isEqualTo(pending.reference());
            when(gateway.generate(any())).thenAnswer(invocation -> {
                assertThat(TransactionScopeSupport.isTransactionActive()).isFalse();
                assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("us");
                assertThat(CurrentUserContext.isSystem()).isFalse();
                assertThat(ActingContextHolder.current()).isEmpty();
                assertThat(ActionExecutionContextHolder.current()).isEmpty();
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                return response(EN);
            });
            var outerAction = ActionExecutionContext.ofPlatformAction(MODULE, PlatformAction.CONTENT_EDIT,
                    Set.of(row.getId()), CurrentUserContext.currentUser());
            var outerActing = new ActingContext("ambient-grant", CurrentUserContext.currentUser().orElseThrow(),
                    BusinessPrincipal.employee("other-principal", "other-org", null), MODULE, "contentEdit");
            try (var action = ActionExecutionContextHolder.use(outerAction); var acting = ActingContextHolder.use(outerActing)) {
                assertThat(restarted().runOne()).isTrue();
                assertThat(ActionExecutionContextHolder.current()).contains(outerAction);
                assertThat(ActingContextHolder.current()).contains(outerActing);
            }
            assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("cn");
            var reading = maintenance.read(service, location(row), "en").fields().get("description");
            assertThat(reading.text()).isEqualTo(EN); assertThat(reading.translation().kind()).isEqualTo("MACHINE");
            assertThat(reading.translation().authorId()).isEqualTo("us"); assertThat(reading.source().authorId()).isEqualTo("cn");
            assertThat(service.select(row.getId()).getDescription()).isEqualTo(RAW);
            assertThat(service.select(row.getId()).getVersion()).isEqualTo(businessVersion);
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("SUCCEEDED");
            verify(gateway, times(1)).generate(any());
        }
    }

    @Test void originalChangeBeforeExecutionDiscardsOldWorkWithoutCallingTheModel() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            var changed = service.select(row.getId()); changed.setDescription(RAW.replace("80", "60"));
            try (var input = input(changed, "zh-CN", 1)) { service.update(changed); }
            assertThat(engine.runOne()).isTrue();
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("STALE");
            verify(gateway, never()).generate(any());
            assertThat(request(changed, false).reference()).isNotEqualTo(pending.reference());
        }
    }

    @Test void delayedOldResultCannotTranslateTheNewOriginalOrCrossAnExplicitRemoval() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            when(gateway.generate(any())).thenAnswer(invocation -> {
                var changed = service.select(row.getId()); changed.setDescription(RAW.replace("80", "60"));
                try (var input = input(changed, "zh-CN", 1)) { service.update(changed); }
                return response(EN);
            });
            engine.runOne();
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("STALE");
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").originalFallback()).isTrue();
            assertThat(translations.history(source(row), new BusinessContentStore.OriginalVersion(
                    content.history(service, row.getId(), "description", PageRequest.of(1, 10)).getLast().revisionId(), 1), "en", PageRequest.of(1, 10))).isEmpty();
            var next = request(row, false);
            doAnswer(invocation -> {
                var prepared = maintenance.prepare(service, location(row), "description", "en", PlatformAction.CONTENT_EDIT);
                maintenance.saveDraft(service, location(row), "description", "en", new BusinessContentTranslationService.Change(
                        prepared.original().revisionId(), 2, null, EN.replace("80", "60"), null));
                maintenance.remove(service, location(row), "description", "en", new BusinessContentTranslationService.Change(
                        prepared.original().revisionId(), 2, 1, null, null));
                return response(EN.replace("80", "60"));
            }).when(gateway).generate(any());
            engine.runOne();
            assertThat(jobDao.findById(next.reference()).getStatus()).isEqualTo("STALE");
            assertThat(request(row, true).state()).isEqualTo("REMOVED");
        }
    }

    @Test void routeWithdrawalStopsWorkAndLaterConfigurationDoesNotAutomaticallyRestartFailedHistory() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            when(gateway.isTextGenerationAvailable()).thenReturn(false);
            engine.runOne();
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("UNAVAILABLE");
            verify(gateway, never()).generate(any());
            when(gateway.isTextGenerationAvailable()).thenReturn(true);
            assertThat(engine.runOne()).isFalse();
            assertThat(request(row, false).state()).isEqualTo("UNAVAILABLE");
            assertThat(request(row, true).reference()).isNotEqualTo(pending.reference());
            engine.runOne(); assertThat(request(row, false).state()).isEqualTo("READY");
        }
    }

    @Test void retriesAreBoundedAndPollingCannotResetTheAttemptBudget() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            when(gateway.generate(any())).thenThrow(new PlatformException("AI_MODEL_TIMEOUT", 504, "private provider diagnostic"));
            engine.runOne(); assertThat(request(row, false).state()).isEqualTo("RETRY");
            assertThat(engine.runOne()).isFalse();
            assertThat(Duration.between(jobs.run(jobDao.findById(pending.reference())).getUpdatedAt(), request(row, false).retryAt()).getSeconds()).isBetween(14L, 16L);
            makeRetryAvailable(pending.reference()); engine.runOne();
            assertThat(jobs.run(jobDao.findById(pending.reference())).getAttempts()).isEqualTo(2);
            assertThat(Duration.between(jobs.run(jobDao.findById(pending.reference())).getUpdatedAt(), request(row, false).retryAt()).getSeconds()).isBetween(29L, 31L);
            makeRetryAvailable(pending.reference()); engine.runOne();
            assertThat(request(row, false).state()).isEqualTo("FAILED");
            assertThat(request(row, false).problem()).isEqualTo("AI_MODEL_TIMEOUT");
            assertThat(engine.runOne()).isFalse(); verify(gateway, times(3)).generate(any());
            assertThat(jobDao.findById(pending.reference()).getRequestJson()).doesNotContain(RAW, "private provider diagnostic");
        }
    }

    @Test void liveIdentityAndEntryWithdrawalBeforeCompletionCannotPersistAResult() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            when(gateway.generate(any())).thenAnswer(invocation -> { identityActive.set(false); return response(EN); });
            engine.runOne(); assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("CANCELLED");
            identityActive.set(true); request(row, true);
            doAnswer(invocation -> { scopeActive.set(false); return response(EN); }).when(gateway).generate(any());
            engine.runOne(); scopeActive.set(true);
            assertThat(request(row, false).state()).isEqualTo("CANCELLED");
            verify(gateway, times(2)).generate(any());
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").originalFallback()).isTrue();
        }
    }

    @Test void completionRechecksTheOriginalWorkspaceAfterWaitingForAConcurrentSave() throws Exception {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN");
            row.setProjectId("project-one"); service.update(row);
            var source = source(row); var completion = new AtomicBoolean(); var intercepted = new AtomicBoolean();
            var scopeRead = new CountDownLatch(1); var writerHolds = new CountDownLatch(1);
            var workerWaiting = new CountDownLatch(1); var releaseWriter = new CountDownLatch(1);
            scopeGuard = location -> {
                if (!"project-one".equals(service.select(location.recordId()).getProjectId()))
                    throw new PlatformAccessDeniedException("original workspace withdrawn");
                if (completion.get() && intercepted.compareAndSet(false, true)) {
                    scopeRead.countDown(); await(writerHolds);
                }
            };
            var pending = request(row, false);
            doAnswer(invocation -> { completion.set(true); return response(EN); }).when(gateway).generate(any());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var worker = executor.submit(() -> {
                    beforeLock = (scope, key) -> {
                        if (completion.get() && Thread.currentThread().getName().equals("content-completion")) workerWaiting.countDown();
                    };
                    Thread.currentThread().setName("content-completion");
                    return engine.runOne();
                });
                Future<?> writer = null;
                try {
                    await(scopeRead);
                    writer = executor.submit(() -> {
                        try (var editor = actor("cn"); var route = TenantContext.use("tenant-a")) {
                            tx.executeWithoutResult(status -> {
                                BusinessContentStore.lockRecord(source);
                                var moved = service.select(row.getId()); moved.setProjectId("project-two"); service.update(moved);
                                writerHolds.countDown(); await(releaseWriter);
                            });
                        }
                    });
                    await(workerWaiting);
                } finally { writerHolds.countDown(); releaseWriter.countDown(); }
                Objects.requireNonNull(writer).get(30, TimeUnit.SECONDS);
                assertThat(worker.get(30, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("CANCELLED");
            assertThat(jobDao.findById(pending.reference()).getProblemCode()).isEqualTo("SCOPE_WITHDRAWN");
            assertThat(service.select(row.getId()).getDescription()).isEqualTo(RAW);
            assertThat(originals.latest(source).getRevision()).isEqualTo(1);
            assertThat(translations.history(source, BusinessContentStore.OriginalVersion.of(originals.latest(source)),
                    "en", PageRequest.of(1, 10))).isEmpty();
            verify(gateway, times(1)).generate(any());
        }
    }

    @Test void concurrentReadersCreateOnlyOneJobForTheSameOriginalAndLanguage() throws Exception {
        try (var author = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(4)) {
                List<Future<String>> requests = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String readerId = "reader-" + i;
                    requests.add(executor.submit(() -> {
                        await(start);
                        try (var reader = actor(readerId); var route = TenantContext.use("tenant-a")) {
                            return request(row, false).reference();
                        }
                    }));
                }
                start.countDown(); Set<String> references = new HashSet<>();
                for (var request : requests) references.add(request.get(30, TimeUnit.SECONDS));
                assertThat(references).hasSize(1);
                assertThat(jobDao.query(Criteria.of().eq("recordId", row.getId()), PageRequest.of(1, 10))).hasSize(1);
            }
        }
    }

    @Test void parallelWorkersClaimDifferentJobsAndKeepTheirTrustedIdentity() throws Exception {
        try (var actor = actor("us"); var tenant = TenantContext.use("tenant-a")) {
            var first = insert(RAW, "zh-CN"); var second = insert(RAW, "zh-CN");
            var firstJob = request(first, false); var secondJob = request(second, false);
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            doAnswer(invocation -> {
                var user = CurrentUserContext.currentUser().orElseThrow();
                assertThat(user.userId()).isEqualTo("us");
                assertThat(user.system()).isFalse();
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                entered.countDown(); await(release);
                return response(EN);
            }).when(gateway).generate(any());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(engine::runOne); var two = executor.submit(engine::runOne);
                try {
                    await(entered);
                    assertThat(jobDao.findById(firstJob.reference()).getStatus()).isEqualTo("RUNNING");
                    assertThat(jobDao.findById(secondJob.reference()).getStatus()).isEqualTo("RUNNING");
                } finally { release.countDown(); }
                assertThat(one.get(30, TimeUnit.SECONDS)).isTrue();
                assertThat(two.get(30, TimeUnit.SECONDS)).isTrue();
            }
            for (var row : List.of(first, second)) {
                var original = originals.latest(source(row));
                assertThat(translations.history(source(row), BusinessContentStore.OriginalVersion.of(original),
                        "en", PageRequest.of(1, 10))).hasSize(1);
            }
            for (var reference : List.of(firstJob.reference(), secondJob.reference())) {
                var job = jobDao.findById(reference);
                assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
                assertThat(jobs.run(job).getAttempts()).isEqualTo(1);
            }
            verify(gateway, times(2)).generate(any());
        }
    }

    @Test void expiredLeaseIsRecoverableAndItsDelayedOldResultCannotAppendAfterTheNewClaim() throws Exception {
        try (var actor = actor("us"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
            var enteredNew = new CountDownLatch(1); var releaseNew = new CountDownLatch(1);
            String recovered = "M-100: At 80°C, 12 parts were tested; 2 failed.";
            doAnswer(invocation -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); return response(EN); }
                enteredNew.countDown(); await(releaseNew);
                return response(recovered);
            }).when(gateway).generate(any());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var oldWorker = executor.submit(engine::runOne);
                Future<Boolean> newWorker = null;
                try {
                    await(entered);
                    assertThat(restarted().runOne()).isFalse();
                    var leased = jobs.run(jobDao.findById(pending.reference()));
                    assertThat(Duration.between(leased.getStartedAt(), leased.getLeaseUntil())).isEqualTo(Duration.ofMinutes(5));
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                            "update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?",
                            jobDao.findById(pending.reference()).getRunId());
                    var recoveredEngine = restarted(); newWorker = executor.submit(recoveredEngine::runOne);
                    await(enteredNew); release.countDown();
                    assertThat(oldWorker.get(30, TimeUnit.SECONDS)).isTrue();
                    var source = source(row);
                    assertThat(translations.history(source, BusinessContentStore.OriginalVersion.of(originals.latest(source)),
                            "en", PageRequest.of(1, 10))).isEmpty();
                    assertThat(jobDao.findById(pending.reference()).getStatus()).isEqualTo("RUNNING");
                } finally { release.countDown(); releaseNew.countDown(); }
                assertThat(Objects.requireNonNull(newWorker).get(30, TimeUnit.SECONDS)).isTrue();
            }
            var job = jobDao.findById(pending.reference());
            assertThat(job.getStatus()).isEqualTo("SUCCEEDED"); assertThat(jobs.run(job).getAttempts()).isEqualTo(2);
            var source = source(row); var original = originals.latest(source);
            assertThat(translations.history(source, BusinessContentStore.OriginalVersion.of(original), "en", PageRequest.of(1, 10)))
                    .hasSize(1).first().extracting(BusinessContentTranslation::getTranslatedText).isEqualTo(recovered);
        }
    }

    @Test void routingTenantBusinessOwnerAndRealAccountIdentityRemainSeparate() {
        try (var actor = actor("us"); var tenant = TenantContext.use("tenant-business-b")) {
            var row = insert(RAW, "zh-CN"); request(row, false);
            doAnswer(invocation -> {
                var user = CurrentUserContext.currentUser().orElseThrow();
                assertThat(user.tenantId()).isEqualTo("tenant-a"); assertThat(user.system()).isFalse();
                assertThat(TenantContext.currentTenantId()).contains("tenant-business-b");
                return response(EN);
            }).when(gateway).generate(any());
            engine.runOne();
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").text()).isEqualTo(EN);
            assertThat(translationDao.query(Criteria.of().eq("recordId", row.getId()), PageRequest.of(1, 10)))
                    .hasSize(1).first().extracting(BusinessContentTranslation::getTenantId).isEqualTo("tenant-business-b");
        }
    }

    @Test void enqueueRollsBackAndFinalDeletionPurgesJobsWithTheOwningTransaction() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN");
            String rolledBack = tx.execute(status -> { var pending = request(row, false); status.setRollbackOnly(); return pending.reference(); });
            assertThat(jobDao.findById(rolledBack)).isNull();
            assertThat(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                    "select count(*) from platform_job_run", Integer.class)).isZero();
            var pending = request(row, false);
            var runId = jobDao.findById(pending.reference()).getRunId();
            var hard = new AbstractAbilityService<ContentGenerationFixture>(MODULE, ContentGenerationFixture.class, dao) { };
            tx.executeWithoutResult(status -> {
                hard.delete(row.getId()); assertThat(jobDao.findById(pending.reference())).isNull();
                assertThat(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                        "select count(*) from platform_job_run where id=?", Integer.class, runId)).isZero(); status.setRollbackOnly();
            });
            assertThat(jobDao.findById(pending.reference())).isNotNull();
            assertThat(jobs.run(jobDao.findById(pending.reference()))).isNotNull();
            hard.delete(row.getId()); assertThat(jobDao.findById(pending.reference())).isNull();
            assertThat(engine.runOne()).isFalse(); verify(gateway, never()).generate(any());
        }
    }

    @Test void terminalOperationalHistoryExpiresWhilePendingWorkAndTranslationFactsAreRetained() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var completedRow = insert(RAW, "zh-CN"); var completed = request(completedRow, false); engine.runOne();
            var pendingRow = insert(RAW, "zh-CN"); var pending = request(pendingRow, false);
            now.updateAndGet(time -> time.plus(Duration.ofDays(31)));
            assertThat(engine.purgeExpired()).isEqualTo(1);
            assertThat(jobDao.findById(completed.reference())).isNull();
            assertThat(jobDao.findById(pending.reference())).isNotNull();
            assertThat(maintenance.read(service, location(completedRow), "en").fields().get("description").text()).isEqualTo(EN);
        }
    }

    @Test void platformAccountCanTriggerWorkInABusinessTenant() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("platform-reader", "platform-reader"));
             var tenant = TenantContext.use("tenant-business")) {
            var row = insert(RAW, "zh-CN"); request(row, false);
            doAnswer(invocation -> {
                assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("platform-reader");
                assertThat(CurrentUserContext.currentUser().orElseThrow().system()).isTrue();
                assertThat(TenantContext.currentTenantId()).contains("tenant-business");
                return response(EN);
            }).when(gateway).generate(any());
            assertThat(engine.runOne()).isTrue();
            assertThat(request(row, false).state()).isEqualTo("READY");
        }
    }

    @Test void platformBusinessScopeUsesThePlatformQueueAndRestoresThePlatformTrigger() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("platform-reader", "platform-reader"));
             var tenant = TenantContext.system("platform content fixture")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            assertThat(jobs.run(jobDao.findById(pending.reference())).getScope()).isEqualTo("PLATFORM");
            doAnswer(invocation -> {
                assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("platform-reader");
                assertThat(TenantContext.isSystem()).isTrue();
                assertThat(TenantContext.currentTenantId()).isEmpty();
                return response(EN);
            }).when(gateway).generate(any());
            assertThat(engine.runOne()).isTrue();
            assertThat(request(row, false).state()).isEqualTo("READY");
        }
    }

    @Test void retryDuringActiveWorkCannotReplaceTheOriginalTriggerIdentity() {
        try (var author = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            try (var reader = actor("other")) {
                assertThat(request(row, true).reference()).isEqualTo(pending.reference());
                doAnswer(invocation -> {
                    assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("cn");
                    return response(EN);
                }).when(gateway).generate(any());
                engine.runOne();
            }
        }
    }

    @Test void statusSelectsTheLatestSequenceAndRetryKeepsTheSameSlot() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var first = request(row, false);
            when(gateway.isTextGenerationAvailable()).thenReturn(false); engine.runOne();
            when(gateway.isTextGenerationAvailable()).thenReturn(true);
            var second = request(row, true);
            assertThat(second.reference()).isNotEqualTo(first.reference());
            assertThat(engine.status(SCOPE, MODULE, location(row), "description", "en").reference()).isEqualTo(second.reference());
            assertThat(jobDao.findById(second.reference()).getSlotKey()).isEqualTo(jobDao.findById(first.reference()).getSlotKey());
            assertThat(jobDao.findById(second.reference()).getSequence()).isEqualTo(2);
        }
    }

    @Test void platformCancellationAndExhaustedLeaseKeepTerminalTopicFactsWithoutTheRun() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            var runId = jobDao.findById(pending.reference()).getRunId();
            queue.getObject().cancel(runId);
            assertThat(request(row, false).state()).isEqualTo("CANCELLED");
            var next = request(row, true);
            var nextRun = jobDao.findById(next.reference()).getRunId();
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("update platform_job_run set status='RUNNING', attempts=3, lease_until=clock_timestamp()-interval '1 second' where id=?", nextRun);
            assertThat(engine.runOne()).isFalse();
            assertThat(request(row, false).state()).isEqualTo("FAILED");
            assertThat(request(row, false).problem()).isEqualTo("ATTEMPTS_EXHAUSTED");
            jdbc.update("update platform_job_run set finished_at=clock_timestamp()-interval '31 days' where id=?", nextRun);
            assertThat(queue.getObject().purgeExpired()).isEqualTo(1);
            assertThat(jobs.run(jobDao.findById(next.reference()))).isNull();
            assertThat(engine.status(SCOPE, MODULE, location(row), "description", "en").state()).isEqualTo("FAILED");
            assertThat(request(row, false).reference()).isEqualTo(next.reference());
            verify(gateway, never()).generate(any());
        }
    }

    @Test void administratorCannotRetryWithAnotherIdentityAndCancellationDuringTheCallPreventsPublication() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            var runId = jobDao.findById(pending.reference()).getRunId();
            doAnswer(invocation -> { queue.getObject().cancel(runId); return response(EN); }).when(gateway).generate(any());
            engine.runOne();
            assertThat(request(row, false).state()).isEqualTo("CANCELLED");
            try (var other = actor("other")) {
                assertThatThrownBy(() -> queue.getObject().retry(runId)).isInstanceOf(IllegalStateException.class);
            }
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").originalFallback()).isTrue();
            assertThat(jobDao.findById(pending.reference()).getRequestJson()).contains("cn").doesNotContain("other");
        }
    }

    @Test void scheduledTopicRetentionKeepsTheRunAndPublishedTranslationFacts() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false); engine.runOne();
            var run = jobs.run(jobDao.findById(pending.reference()));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("update platform_business_content_generation set finished_at=clock_timestamp()-interval '31 days' where id=?", pending.reference());
            queue.getObject().registerDefaults();
            jdbc.update("update platform_job_plan set next_fire_at=clock_timestamp()-interval '1 second'");
            queue.getObject().scan();
            assertThat(queue.getObject().runOne("default", "retention-test")).isTrue();
            assertThat(jobDao.findById(pending.reference())).isNull();
            assertThat(jdbc.queryForObject("select count(*) from platform_job_run where id=?", Integer.class, run.getId())).isEqualTo(1);
            assertThat(maintenance.read(service, location(row), "en").fields().get("description").text()).isEqualTo(EN);
        }
    }

    @Test void takeoverAfterTopicPublicationDoesNotRepeatACompletedOrNonRetryableModelCall() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            for (boolean valid : List.of(true, false)) {
                var row = insert(RAW, "zh-CN"); var pending = request(row, false);
                when(gateway.generate(any())).thenReturn(response(valid ? EN : "invalid output"));
                engine.runOne();
                var topic = jobDao.findById(pending.reference());
                assertThat(topic.getStatus()).isIn("SUCCEEDED", "FAILED");
                new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                        "update platform_job_run set status='RUNNING', attempts=1, lease_until=clock_timestamp()-interval '1 second' where id=?", topic.getRunId());
                assertThat(engine.runOne()).isTrue();
                assertThat(jobDao.findById(topic.getId()).getStatus()).isEqualTo(topic.getStatus());
                assertThat(jobs.run(topic).getStatus()).isEqualTo(topic.getStatus());
            }
            verify(gateway, times(2)).generate(any());
        }
    }

    @Test void finalDeletionCanRemoveTheRunWhileCompletionWaitsForTheBusinessLock() throws Exception {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = insert(RAW, "zh-CN"); var pending = request(row, false);
            String runId = jobDao.findById(pending.reference()).getRunId();
            var source = source(row); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writerHolds = new CountDownLatch(1); var workerWaiting = new CountDownLatch(1);
            var completing = new AtomicBoolean();
            doAnswer(invocation -> { entered.countDown(); await(release); completing.set(true); return response(EN); }).when(gateway).generate(any());
            beforeLock = (scope, key) -> {
                if (completing.get() && Thread.currentThread().getName().equals("content-deletion-completion")) workerWaiting.countDown();
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var worker = executor.submit(() -> { Thread.currentThread().setName("content-deletion-completion"); return engine.runOne(); });
                await(entered);
                var writer = executor.submit(() -> {
                    try (var user = actor("cn"); var route = TenantContext.use("tenant-a")) {
                        tx.executeWithoutResult(status -> {
                            BusinessContentStore.lockRecord(source); writerHolds.countDown(); await(workerWaiting);
                            new AbstractAbilityService<ContentGenerationFixture>(MODULE, ContentGenerationFixture.class, dao) { }.delete(row.getId());
                        });
                    }
                });
                try { await(writerHolds); release.countDown(); writer.get(30, TimeUnit.SECONDS); }
                finally { release.countDown(); workerWaiting.countDown(); }
                assertThat(worker.get(30, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(jobDao.findById(pending.reference())).isNull();
            assertThat(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                    "select count(*) from platform_job_run where id=?", Integer.class, runId)).isZero();
            assertThat(translationDao.query(Criteria.of().eq("recordId", row.getId()), PageRequest.of(1, 10))).isEmpty();
        }
    }

    private void makeRetryAvailable(String reference) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "update platform_job_run set available_at=clock_timestamp()-interval '1 second' where id=?", jobDao.findById(reference).getRunId());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("translation concurrency barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private BusinessContentGenerationService restarted() {
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var result = new BusinessContentGenerationService(originals, translations, jobs, identityProvider, scopeProvider,
                gateway, json, transactions, clock, queue);
        jobConfiguration.engine = result; return result;
    }
    private ContentGenerationFixture insert(String text, String language) {
        var row = new ContentGenerationFixture(); row.setDescription(text);
        try (var input = input(row, language, null)) { service.insert(row); } return row;
    }
    private static ContentWriteContext.Scope input(ContentGenerationFixture row, String language, Integer revision) {
        return ContentWriteContext.open(row, Map.of("description", new ContentWriteContext.SourceInput(language, revision)));
    }
    private BusinessContentGenerationService.Status request(ContentGenerationFixture row, boolean retry) {
        return engine.request(SCOPE, MODULE, location(row), "description", "en", retry);
    }
    private BusinessContentSource source(ContentGenerationFixture row) {
        return BusinessContentSaveLifecycleListener.source(service, service.select(row.getId()), "quality-description");
    }
    private static BusinessContentService.Location location(ContentGenerationFixture row) { return BusinessContentService.Location.record(row.getId()); }
    private static CurrentUserContext.Scope actor(String id) { return CurrentUserContext.use(CurrentUser.tenantUser(id, id, "tenant-a")); }
    private static AiTextResponse response(String text) { return new AiTextResponse("{\"text\":\"" + text + "\"}", "stop", "provider-id"); }
    private static class FixtureService extends AbstractAbilityService<ContentGenerationFixture>
            implements SoftDeleteAbility<ContentGenerationFixture>, DataScopeAbility<ContentGenerationFixture>, FieldReadAbility {
        boolean descriptionReadable = true;
        FixtureService(ContentGenerationFixtureDao dao) { super(MODULE, ContentGenerationFixture.class, dao); }
        public FieldReadPolicy fieldReadPolicy(ActionExecutionContext action) {
            return descriptionReadable ? FieldReadPolicy.allReadable() : FieldReadPolicy.readableFields(List.of("id"));
        }
    }
}
