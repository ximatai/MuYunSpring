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
        gateway = mock(AiModelGateway.class); when(gateway.isTextGenerationAvailable()).thenReturn(true);
        when(gateway.generate(any())).thenReturn(response(EN));
        service = new FixtureService(dao); tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return tx.execute(status -> work.get()); }
            public void lock(String scope, String key) {
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
                                    String field, Function<CrudAbility<?>, T> work) {
                if (!scopeActive.get()) throw new PlatformAccessDeniedException("scope withdrawn");
                assertThat(CurrentUserContext.currentUser()).isPresent();
                try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPlatformAction(
                        module, PlatformAction.VIEW, Set.of(location.recordId()), CurrentUserContext.currentUser()))) {
                    return work.apply(service);
                }
            }
        };
        when(scopeProvider.orderedStream()).thenAnswer(invocation -> Stream.of(adapter));
        engine = restarted();
        jobDao.query(Criteria.of(), PageRequest.of(1, 10_000)).forEach(row -> jobDao.deleteById(row.getId()));
    }
    @AfterEach void reset() {
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener(); PlatformAbilityRuntime.resetDeletionLifecycleListener();
        PlatformAbilityRuntime.resetMutationTransactionOperator(); PlatformAbilityRuntime.resetDataScopeCriteriaService();
        CurrentUserContext.clear(); ActingContextHolder.clear(); ActionExecutionContextHolder.clear(); TenantContext.clear();
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
            now.updateAndGet(time -> time.plusSeconds(15)); engine.runOne();
            assertThat(jobDao.findById(pending.reference()).getAttempts()).isEqualTo(2);
            now.updateAndGet(time -> time.plusSeconds(30)); engine.runOne();
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
                    now.updateAndGet(time -> time.plus(BusinessContentGenerationStore.LEASE).plusSeconds(1));
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
            assertThat(job.getStatus()).isEqualTo("SUCCEEDED"); assertThat(job.getAttempts()).isEqualTo(2);
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
            var pending = request(row, false);
            var hard = new AbstractAbilityService<ContentGenerationFixture>(MODULE, ContentGenerationFixture.class, dao) { };
            tx.executeWithoutResult(status -> {
                hard.delete(row.getId()); assertThat(jobDao.findById(pending.reference())).isNull(); status.setRollbackOnly();
            });
            assertThat(jobDao.findById(pending.reference())).isNotNull();
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
        return new BusinessContentGenerationService(content, originals, translations, jobs, identityProvider, scopeProvider,
                gateway, json, transactions, clock);
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
            implements SoftDeleteAbility<ContentGenerationFixture>, DataScopeAbility<ContentGenerationFixture> {
        FixtureService(ContentGenerationFixtureDao dao) { super(MODULE, ContentGenerationFixture.class, dao); }
    }
}
