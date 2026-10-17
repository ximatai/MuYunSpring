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
    @Autowired BusinessContentTranslationStore translations;
    @Autowired BusinessContentTranslationDao translationDao;
    @Autowired BusinessContentService content;
    @Autowired BusinessContentQueryService queries;
    @Autowired BusinessContentTranslationService translationService;
    @Autowired BusinessContentSaveLifecycleListener listener;
    @Autowired BusinessContentDeletionObserver deletion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IDatabaseOperations<?> operations;
    @Autowired DataSource dataSource;
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
            public void lock(String scope, String key) {
                new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                        "select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
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
    @Test void originalChangesExposeHumanReferenceOnlyToMaintenanceWithoutCarryingConfirmationForward() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("80°C，暂勿发货"); service.insert(row);
            var location = BusinessContentService.Location.record(row.getId());
            var initial = translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT);
            var draft = translationService.saveDraft(service, location, "title", "en", change(initial, "80°C; do not ship yet", null));
            var confirmed = translationService.confirm(service, location, "title", "en", change(draft, null, 1));
            var edited = service.select(row.getId()); edited.setTitle("60°C，暂勿发货"); service.update(edited);
            var reading = translationService.read(service, location, "en").fields().get("title");
            assertThat(reading.text()).isEqualTo("60°C，暂勿发货"); assertThat(reading.translation()).isNull();
            assertThat(reading.humanReviewRequired()).isTrue();
            var maintenance = translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT);
            assertThat(maintenance.original().revisionId()).isNotEqualTo(initial.original().revisionId());
            assertThat(maintenance.expectedRevision()).isNull(); assertThat(maintenance.draft()).isNull();
            assertThat(maintenance.confirmed()).isNull(); assertThat(maintenance.machine()).isNull();
            assertThat(maintenance.previousHuman().original()).isEqualTo(initial.original());
            assertThat(maintenance.previousHuman().translation().text()).isEqualTo("80°C; do not ship yet");
            assertThat(maintenance.previousHuman().translation().kind()).isEqualTo("CONFIRMED");
            assertThatThrownBy(() -> translationService.confirm(service, location, "title", "en", change(confirmed, null, 1)))
                    .isInstanceOf(OptimisticLockException.class);
            assertThatThrownBy(() -> translationService.confirm(service, location, "title", "en", change(maintenance, null, 1)))
                    .isInstanceOf(OptimisticLockException.class);
            var currentDraft = translationService.saveDraft(service, location, "title", "en", change(maintenance, "60°C; do not ship yet", null));
            assertThat(translationService.read(service, location, "en").fields().get("title").translation()).isNull();
            var currentConfirmed = translationService.confirm(service, location, "title", "en", change(currentDraft, null, 1));
            assertThat(translationService.read(service, location, "en").fields().get("title").text()).isEqualTo("60°C; do not ship yet");
            assertThat(translationService.read(service, location, "en").fields().get("title").humanReviewRequired()).isFalse();
            assertThat(translationService.history(service, location, "title", "en", initial.original().revisionId(),
                    initial.original().revision(), HISTORY, PlatformAction.CONTENT_EDIT)).hasSize(2);
            var removed = translationService.remove(service, location, "title", "en", change(currentConfirmed, null, null));
            assertThat(removed.previousHuman()).isNull();
            assertThat(translationService.history(service, location, "title", "en", initial.original().revisionId(),
                    initial.original().revision(), HISTORY, PlatformAction.CONTENT_EDIT)).hasSize(2);
            assertThat(translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT).previousHuman()).isNull();
            assertThat(translationService.read(service, location, "en").fields().get("title").humanReviewRequired()).isFalse();
            var restarted = translationService.saveDraft(service, location, "title", "en", change(removed, "New explicit draft", null));
            assertThat(restarted.previousHuman()).isNull();
            assertThat(restarted.draft().text()).isEqualTo("New explicit draft");
            assertThat(translationService.read(service, location, "en").fields().get("title").humanReviewRequired()).isFalse();
            translationService.remove(service, location, "title", "en", change(restarted, null, null));
            edited = service.select(row.getId()); edited.setTitle("50°C，暂勿发货"); service.update(edited);
            assertThat(translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT).previousHuman()).isNull();
            assertThat(translationService.read(service, location, "en").fields().get("title").humanReviewRequired()).isFalse();
            assertThat(translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT).previousHuman()).isNull();
            assertThat(service.select(row.getId()).getTitle()).isEqualTo("50°C，暂勿发货");
            try (var other = TenantContext.use("tenant-b")) {
                assertThatThrownBy(() -> translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
        }
    }

    @Test void earlierPrivateDraftIsAReferenceForMaintainersWithoutDisclosingItsExistenceToOrdinaryReaders() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("旧原文"); service.insert(row);
            var location = BusinessContentService.Location.record(row.getId());
            var initial = translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT);
            translationService.saveDraft(service, location, "title", "de", change(initial, "Privater Entwurf", null));
            var changed = service.select(row.getId()); changed.setTitle("新原文"); service.update(changed);
            var reading = translationService.read(service, location, "de").fields().get("title");
            assertThat(reading.humanReviewRequired()).isFalse(); assertThat(reading.translation()).isNull();
            assertThat(reading.text()).isEqualTo("新原文");
            var maintenance = translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_CONFIRM);
            assertThat(maintenance.previousHuman().translation().kind()).isEqualTo("DRAFT");
            assertThat(maintenance.previousHuman().translation().text()).isEqualTo("Privater Entwurf");
            assertThat(maintenance.draft()).isNull(); assertThat(maintenance.confirmed()).isNull();
        }
    }

    @Test void currentTranslationStateUsesBoundedDatabaseReadsRegardlessOfHistoryLengthAndRespectsRemoval() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("原文"); service.insert(row);
            var source = source(row); var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Human original", null));
            tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 1, 1));
            var template = tx.execute(status -> translations.saveMachine(source, original, "en", "Machine refresh", 2, generation()));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Human work in progress", 3));
            tx.executeWithoutResult(status -> {
                for (int revision = 5; revision <= 605; revision++) {
                    var fact = new BusinessContentTranslation(); org.springframework.beans.BeanUtils.copyProperties(template, fact);
                    fact.setId(null); fact.setVersion(null); fact.setRevision(revision);
                    net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(fact, java.time.Instant.now());
                    translationDao.insert(fact);
                }
            });
            List<PageRequest> pages = new ArrayList<>();
            var counted = org.mockito.Mockito.mock(BusinessContentTranslationDao.class, invocation -> {
                if (invocation.getMethod().getName().equals("query")) pages.add(invocation.getArgument(1));
                return org.mockito.AdditionalAnswers.delegatesTo(translationDao).answer(invocation);
            });
            var bounded = new BusinessContentTranslationStore(counted, store);
            var state = bounded.state(source, original, "en");
            assertThat(state.revision()).isEqualTo(605);
            assertThat(state.confirmed().getTranslatedText()).isEqualTo("Human original");
            assertThat(state.draft().getTranslatedText()).isEqualTo("Human work in progress");
            assertThat(state.machine().getRevision()).isEqualTo(605);
            assertThat(pages).hasSizeLessThanOrEqualTo(5).allSatisfy(page -> {
                assertThat(page.getLimit()).isEqualTo(1); assertThat(page.getOffset()).isZero();
            });
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var interleaved = org.mockito.Mockito.mock(BusinessContentTranslationDao.class, invocation -> {
                if (invocation.getMethod().getName().equals("query") && calls.incrementAndGet() == 2)
                    tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Concurrent new draft", 605));
                return org.mockito.AdditionalAnswers.delegatesTo(translationDao).answer(invocation);
            });
            var snapshot = new BusinessContentTranslationStore(interleaved, store).state(source, original, "en");
            assertThat(snapshot.revision()).isEqualTo(605);
            assertThat(snapshot.draft().getTranslatedText()).isEqualTo("Human work in progress");
            assertThat(translations.state(source, original, "en").draft().getTranslatedText()).isEqualTo("Concurrent new draft");
            tx.executeWithoutResult(status -> translations.remove(source, original, "en", 606));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Explicit new draft", 607));
            state = bounded.state(source, original, "en");
            assertThat(state.draft().getTranslatedText()).isEqualTo("Explicit new draft");
            assertThat(state.confirmed()).isNull(); assertThat(state.machine()).isNull();
        }
    }

    @Test void humanDraftDoesNotReplaceReadingAndConfirmationRetainsBothAuthorsAndHistory() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("80°C，暂勿发货"); service.insert(row);
            var source = source(row);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            var machine = tx.execute(status -> translations.saveMachine(source, original, "en", "80°C; do not ship yet", null, generation()));
            assertThat(machine.getGenerationReference()).isEqualTo("test-generation");
            assertThat(machine.getProviderRequestId()).isEqualTo("request-1");
            try (var editor = actor("us")) {
                tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Hold shipment until inspected at 80°C", 1));
            }
            var state = translations.state(source, original, "en");
            assertThat(state.effective().getTranslatedText()).isEqualTo("80°C; do not ship yet");
            assertThat(state.draft().getCreatedBy()).isEqualTo("us");
            assertThatThrownBy(() -> tx.execute(status -> translations.confirm(source, original, "en", 2, 1)))
                    .isInstanceOf(OptimisticLockException.class);
            try (var reviewer = actor("de")) {
                tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 2, 2));
            }
            state = translations.state(source, original, "en");
            assertThat(state.draft()).isNull();
            assertThat(state.effective().getCreatedBy()).isEqualTo("de");
            assertThat(state.effective().getReviewedDraftRevision()).isEqualTo(2);
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "New unconfirmed human edit", 3));
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "Machine refresh", 4, generation()));
            state = translations.state(source, original, "en");
            assertThat(state.effective().getTranslatedText()).isEqualTo("Hold shipment until inspected at 80°C");
            assertThat(state.draft().getTranslatedText()).isEqualTo("New unconfirmed human edit");
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "de", "Bis zur Prüfung nicht versenden", null));
            assertThat(translations.state(source, original, "de").effective()).isNull();
            assertThat(translations.history(source, original, "en", HISTORY)).extracting(BusinessContentTranslation::getKind)
                    .containsExactly("MACHINE", "DRAFT", "CONFIRMED", "DRAFT", "MACHINE");
            assertThat(service.select(row.getId()).getTitle()).isEqualTo("80°C，暂勿发货");
            assertThat(history(row)).hasSize(1);
        }
    }

    @Test void explicitTranslationRemovalKeepsHistoryAndRejectsLateGenerationWithoutTouchingOtherLanguage() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("原文"); service.insert(row); var source = source(row);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "Original", null, generation()));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "de", "Originaltext", null));
            tx.executeWithoutResult(status -> translations.remove(source, original, "en", 1));
            assertThat(translations.state(source, original, "en").effective()).isNull();
            assertThat(translations.state(source, original, "en").revision()).isEqualTo(2);
            assertThat(translations.state(source, original, "de").draft().getTranslatedText()).isEqualTo("Originaltext");
            assertThatThrownBy(() -> tx.execute(status -> translations.saveMachine(source, original, "en", "Late result", 1, generation())))
                    .isInstanceOf(OptimisticLockException.class);
            assertThatThrownBy(() -> tx.execute(status -> translations.saveDraft(source, original, "en", "Lost update", null)))
                    .isInstanceOf(OptimisticLockException.class);
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Recreated explicitly", 2));
            assertThat(translations.state(source, original, "en").effective()).isNull();
            assertThat(translations.history(source, original, "en", HISTORY)).hasSize(3);
        }
    }

    @Test void originalChangeInvalidatesTranslationsWhileHistoricalFactsAndTransactionalRecoveryRemainAvailable() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("80°C"); service.insert(row); var source = source(row);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "80°C", null));
            tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 1, 1));
            tx.executeWithoutResult(status -> {
                translations.saveDraft(source, original, "en", "Rolled back", 2);
                status.setRollbackOnly();
            });
            assertThat(translations.state(source, original, "en").revision()).isEqualTo(2);
            row.setTitle("60°C"); service.update(row);
            assertThat(translations.state(source, BusinessContentStore.OriginalVersion.of(store.latest(source)), "en").effective()).isNull();
            assertThat(translations.state(source, original, "en").confirmed().getTranslatedText()).isEqualTo("80°C");
            assertThatThrownBy(() -> tx.execute(status -> translations.saveMachine(source, original, "en", "Old original result", 2, generation())))
                    .isInstanceOf(OptimisticLockException.class);
            assertThatThrownBy(() -> tx.execute(status -> translations.confirm(source, original, "en", 2, 1)))
                    .isInstanceOf(OptimisticLockException.class);
            service.delete(row.getId());
            assertThatThrownBy(() -> content.sources(service, row.getId()))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            service.restore(row.getId());
            assertThat(translations.history(source, original, "en", HISTORY)).hasSize(2);
            var hard = new AbstractAbilityService<ContentFixture>("test.content", ContentFixture.class, dao) { };
            tx.executeWithoutResult(status -> {
                hard.delete(row.getId());
                assertThat(translations.history(source, original, "en", HISTORY)).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(translations.history(source, original, "en", HISTORY)).hasSize(2);
            hard.delete(row.getId());
            assertThat(translations.history(source, original, "en", HISTORY)).isEmpty();
            assertThat(translations.history(source, original, "de", HISTORY)).isEmpty();
        }
    }

    @Test void delayedResultCannotAttachToANewRecordThatReusesAFinallyPurgedId() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var retained = new RetainedFixtureService(dao);
            var old = row("旧业务原文"); retained.insert(old); var source = source(old);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            retained.delete(old.getId()); retained.purge(old.getId());
            var replacement = row("相同记录 ID 的新业务原文"); replacement.setId(old.getId()); retained.insert(replacement);
            assertThat(store.latest(source).getRevision()).isEqualTo(1);
            var replacementOriginal = BusinessContentStore.OriginalVersion.of(store.latest(source));
            assertThat(replacementOriginal.revisionId()).isNotEqualTo(original.revisionId());
            assertThatThrownBy(() -> tx.execute(status -> translations.saveMachine(source, original, "en", "Old record result", null, generation())))
                    .isInstanceOf(OptimisticLockException.class);
            assertThat(translations.state(source, replacementOriginal, "en").effective()).isNull();
            tx.executeWithoutResult(status -> translations.saveDraft(source, replacementOriginal, "en", "New record draft", null));
            assertThat(translations.state(source, original, "en").draft()).isNull();
            assertThat(translations.state(source, replacementOriginal, "en").draft().getTranslatedText()).isEqualTo("New record draft");
        }
    }

    @Test void actualRecycleBinPurgeAtomicallyRemovesOriginalAndTranslationCopies() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var retained = new RetainedFixtureService(dao);
            var row = row("需要清理的业务文本"); retained.insert(row); var source = source(row);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Retained text", null));
            retained.delete(row.getId());
            assertThat(translations.state(source, original, "en").draft()).isNotNull();
            tx.executeWithoutResult(status -> {
                retained.purge(row.getId());
                assertThat(store.latest(source)).isNull();
                assertThat(translations.history(source, original, "en", HISTORY)).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(retained.selectIgnoreSoftDelete(row.getId())).isNotNull();
            assertThat(store.latest(source)).isNotNull();
            assertThat(translations.state(source, original, "en").draft()).isNotNull();
            retained.purge(row.getId());
            assertThat(retained.selectIgnoreSoftDelete(row.getId())).isNull();
            assertThat(store.latest(source)).isNull();
            assertThat(translations.history(source, original, "en", HISTORY)).isEmpty();
        }
    }

    @Test void concurrentTranslationMaintenanceUsesOneReviewedVersion() throws Exception {
        BusinessContentSource source;
        BusinessContentStore.OriginalVersion original;
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("原文"); service.insert(row); source = source(row);
            original = BusinessContentStore.OriginalVersion.of(store.latest(source));
        }
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> writes = new ArrayList<>();
            for (String value : List.of("First draft", "Second draft")) writes.add(executor.submit(() -> {
                try (var user = actor(value); var tenant = TenantContext.use("tenant-a")) {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", value, null));
                        return true;
                    } catch (OptimisticLockException expected) { return false; }
                }
            }));
            assertThat(List.of(writes.get(0).get(15, TimeUnit.SECONDS), writes.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            assertThat(translations.history(source, original, "en", HISTORY)).hasSize(1);
            try (var other = TenantContext.use("tenant-b")) {
                var otherSource = new BusinessContentSource("tenant-b", source.moduleAlias(), source.entityKey(), source.recordId(), source.fieldKey());
                assertThat(translations.state(otherSource, original, "en").draft()).isNull();
                assertThatThrownBy(() -> tx.execute(status -> translations.saveDraft(otherSource, original, "en", "No owner", null)))
                        .isInstanceOf(OptimisticLockException.class);
            }
        }
    }

    private BusinessContentSource source(ContentFixture row) {
        return BusinessContentSaveLifecycleListener.source(service, row, "category-name");
    }
    @Test void delayedGenerationWaitsForCommittedOriginalAndCannotAttachToItsReplacement() throws Exception {
        String id;
        BusinessContentSource source;
        BusinessContentStore.OriginalVersion original;
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("80°C"); service.insert(row); id = row.getId(); source = source(row);
            original = BusinessContentStore.OriginalVersion.of(store.latest(source));
        }
        var originalWritten = new CountDownLatch(1);
        var generationStarted = new CountDownLatch(1);
        var commitOriginal = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var edit = executor.submit(() -> {
                try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
                    tx.executeWithoutResult(status -> {
                        var row = service.select(id); row.setTitle("60°C"); service.update(row);
                        originalWritten.countDown();
                        try { if (!commitOriginal.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    });
                }
            });
            assertThat(originalWritten.await(10, TimeUnit.SECONDS)).isTrue();
            var late = executor.submit(() -> {
                try (var user = actor("us"); var tenant = TenantContext.use("tenant-a")) {
                    generationStarted.countDown();
                    try {
                        tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "80°C", null, generation()));
                        return true;
                    } catch (OptimisticLockException expected) { return false; }
                }
            });
            try {
                assertThat(generationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> late.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { commitOriginal.countDown(); }
            edit.get(10, TimeUnit.SECONDS);
            assertThat(late.get(10, TimeUnit.SECONDS)).isFalse();
        }
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            assertThat(translations.history(source, original, "en", HISTORY)).isEmpty();
            assertThat(translations.state(source, BusinessContentStore.OriginalVersion.of(store.latest(source)), "en").effective()).isNull();
            assertThat(service.select(id).getTitle()).isEqualTo("60°C");
        }
    }
    private static BusinessContentTranslationStore.Generation generation() {
        return new BusinessContentTranslationStore.Generation("test-generation", "request-1", "translation-v1", "context-v1");
    }

    @Test void publicReadingKeepsDraftsPrivateAndMaintenanceNeverWritesRawBusinessFields() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("数控设备");
            try (var input = input(row, "zh-CN", null)) { service.insert(row); }
            var location = BusinessContentService.Location.record(row.getId());
            var businessVersion = service.select(row.getId()).getVersion();
            var first = translationService.prepare(service, location, "title", "en", PlatformAction.CONTENT_EDIT);
            assertThat(first.original().revisionId()).isNotBlank();
            var draft = translationService.saveDraft(service, location, "title", "en", change(first, "CNC Equipment", null));
            var reading = translationService.read(service, location, "en").fields().get("title");
            assertThat(reading.originalFallback()).isTrue(); assertThat(reading.text()).isEqualTo("数控设备");
            assertThat(reading.translation()).isNull();
            assertThat(translationService.read(service, location, "zh-CN").fields().get("title").originalFallback()).isFalse();
            try (var confirmer = actor("us")) {
                var confirmed = translationService.confirm(service, location, "title", "en", change(draft, null, draft.draft().revision()));
                assertThat(confirmed.confirmed().authorId()).isEqualTo("us");
                assertThat(translationService.history(service, location, "title", "en", first.original().revisionId(),
                        first.original().revision(), HISTORY, PlatformAction.CONTENT_CONFIRM)).extracting(BusinessContentTranslationService.TranslationRevision::authorId)
                        .containsExactly("us", "cn");
                var next = translationService.saveDraft(service, location, "title", "en", change(confirmed, "CNC Machines", null));
                reading = translationService.read(service, location, "en").fields().get("title");
                assertThat(reading.text()).isEqualTo("CNC Equipment"); assertThat(reading.originalText()).isEqualTo("数控设备");
                assertThat(reading.translation().kind()).isEqualTo("CONFIRMED");
                assertThatThrownBy(() -> translationService.confirm(service, location, "title", "en", change(draft, null, 1)))
                        .isInstanceOf(OptimisticLockException.class);
                var removed = translationService.remove(service, location, "title", "en", change(next, null, null));
                assertThat(removed.draft()).isNull(); assertThat(removed.confirmed()).isNull();
                assertThat(translationService.read(service, location, "en").fields().get("title").text()).isEqualTo("数控设备");
            }
            assertThat(service.select(row.getId()).getTitle()).isEqualTo("数控设备");
            assertThat(service.select(row.getId()).getVersion()).isEqualTo(businessVersion);
            assertThat(history(row)).hasSize(1);
            var updated = service.select(row.getId()); updated.setTitle("CNC source");
            try (var input = input(updated, "en-US", 1)) { service.update(updated); }
            assertThat(translationService.read(service, location, "en").fields().get("title").originalFallback()).isFalse();
            assertThatThrownBy(() -> translationService.saveDraft(service, location, "title", "en", change(first, "stale", null)))
                    .isInstanceOf(OptimisticLockException.class);
        }
    }
    @Test void publicTranslationAuthorizationUsesIndependentActionsRecordScopeTenantAndLiveField() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("受权译文"); service.insert(row);
            var location = BusinessContentService.Location.record(row.getId());
            var initial = translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT);
            var draft = translationService.saveDraft(service, location, "title", "de", change(initial, "Autorisierter Text", null));
            var viewOnly = new BusinessContentService(store, context -> {
                if (context.platformAction() != PlatformAction.VIEW)
                    throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("No maintenance right");
            }, new AllowAllDataScopeCriteriaService());
            var viewTranslations = new BusinessContentTranslationService(viewOnly, store, translations, transactions);
            assertThat(viewTranslations.read(service, location, "de").fields().get("title").translation()).isNull();
            assertThatThrownBy(() -> viewTranslations.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> viewTranslations.confirm(service, location, "title", "de", change(draft, null, 1)))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            var scoped = new FixtureService(dao) {
                @Override public DataScopeCriteriaResult readScope(PlatformAction action, Criteria criteria) {
                    return DataScopeCriteriaResult.unrestricted(action == PlatformAction.VIEW ? criteria : criteria.eq("id", "invisible"));
                }
            };
            assertThatThrownBy(() -> translationService.prepare(scoped, location, "title", "de", PlatformAction.CONTENT_EDIT))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> translationService.prepare(service, location, "quantity", "de", PlatformAction.CONTENT_EDIT))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            try (var other = TenantContext.use("tenant-b")) {
                assertThatThrownBy(() -> translationService.read(service, location, "de"))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                assertThatThrownBy(() -> translationService.history(service, location, "title", "de", initial.original().revisionId(),
                        initial.original().revision(), HISTORY, PlatformAction.CONTENT_EDIT))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
            service.delete(row.getId());
            assertThatThrownBy(() -> translationService.read(service, location, "de"))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            service.restore(row.getId());
            assertThat(translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT).draft().text()).isEqualTo("Autorisierter Text");
        }
    }
    @Test void translationMaintenanceRetainsTheTrustedResolvedActionPolicyWithoutBorrowingItForConfirmation() {
        try (var user = actor("contributor"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("Policy-scoped content"); service.insert(row);
            var location = BusinessContentService.Location.record(row.getId());
            var resolved = new ActionExecutionPolicy("contentEdit", PlatformActionLevel.RECORD, ActionAccessMode.AUTH_REQUIRED,
                    true, false, ActionDefaultGrantPolicy.NONE, "translationTeam");
            var policies = (ActionExecutionPolicyService) context -> {
                if (context.platformAction() != PlatformAction.VIEW && !context.actionPolicy().equals(resolved))
                    throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("Requires the resolved policy");
            };
            var scoped = new FixtureService(dao) {
                @Override public DataScopeCriteriaResult readScope(PlatformAction action, Criteria criteria) {
                    return DataScopeCriteriaResult.unrestricted(action == PlatformAction.VIEW ? criteria : criteria.eq("id", "invisible"));
                }
                @Override public DataScopeCriteriaResult readScopeByPolicy(ActionExecutionPolicy policy, Criteria criteria) {
                    assertThat(policy).isEqualTo(resolved);
                    return DataScopeCriteriaResult.unrestricted(criteria);
                }
            };
            var facade = new BusinessContentTranslationService(new BusinessContentService(store, policies,
                    new AllowAllDataScopeCriteriaService()), store, translations, transactions);
            try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(service.getModuleAlias(), resolved,
                    Set.of(row.getId()), CurrentUserContext.currentUser()))) {
                var state = facade.prepare(scoped, location, "title", "de", PlatformAction.CONTENT_EDIT);
                facade.saveDraft(scoped, location, "title", "de", change(state, "Inhalt", null));
                assertThatThrownBy(() -> facade.prepare(scoped, location, "title", "de", PlatformAction.CONTENT_CONFIRM))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
        }
    }

    @Test void preparingLegacyMaintenanceCapturesUnknownProvenanceWithoutClaimingOriginalAuthorship() {
        try (var user = actor("viewer"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("Legacy category");
            net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(row, java.time.Instant.now());
            row.setTenantId("tenant-a"); dao.insert(row);
            var location = BusinessContentService.Location.record(row.getId());
            assertThat(translationService.read(service, location, "de").fields().get("title").source()).isNull();
            assertThat(history(row)).isEmpty();
            var state = translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_EDIT);
            assertThat(state.original().revisionKind()).isEqualTo("UNTRACKED_BASELINE");
            assertThat(state.original().authorId()).isNull(); assertThat(state.original().authoredAt()).isNull();
            assertThat(state.original().revisionAuthorId()).isEqualTo("viewer");
            assertThat(state.original().text()).isEqualTo("Legacy category");
            assertThat(service.select(row.getId()).getVersion()).isEqualTo(row.getVersion());
            assertThat(translationService.prepare(service, location, "title", "de", PlatformAction.CONTENT_CONFIRM).original().revisionId())
                    .isEqualTo(state.original().revisionId());
            assertThat(history(row)).hasSize(1);
        }
    }
    private static BusinessContentTranslationService.Change change(BusinessContentTranslationService.Maintenance state,
            String text, Integer reviewedDraft) {
        return new BusinessContentTranslationService.Change(state.original().revisionId(), state.original().revision(),
                state.expectedRevision(), text, reviewedDraft);
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
            var parentSource = BusinessContentSaveLifecycleListener.source(runtime.entityService(module.moduleAlias(), "main"), parent, policy.key());
            var childTextSource = BusinessContentSaveLifecycleListener.source(runtime.entityService(module.moduleAlias(), "line"), child, policy.key());
            var parentOriginal = BusinessContentStore.OriginalVersion.of(store.latest(parentSource));
            var childOriginal = BusinessContentStore.OriginalVersion.of(store.latest(childTextSource));
            tx.executeWithoutResult(status -> translations.saveMachine(parentSource, parentOriginal, "en", "80°C; do not ship yet", null, generation()));
            tx.executeWithoutResult(status -> translations.saveDraft(childTextSource, childOriginal, "de", "Untersuchung ausstehend", null));
            tx.executeWithoutResult(status -> translations.confirm(childTextSource, childOriginal, "de", 1, 1));
            assertThat(translations.state(parentSource, parentOriginal, "en").effective().getTranslatedText()).isEqualTo("80°C; do not ship yet");
            assertThat(translations.state(childTextSource, childOriginal, "de").effective().getTranslatedText()).isEqualTo("Untersuchung ausstehend");
            assertThat(translations.state(parentSource, parentOriginal, "de").effective()).isNull();
            assertThat(translations.state(childTextSource, childOriginal, "en").effective()).isNull();
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
            assertThat(aggregateContent.childSelection(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", 10).ids())
                    .contains(child.getId());
            assertThat(childSource.fields().get("description").source()).isEqualTo(childHistory.getFirst());
            assertThat(aggregateContent.childHistory(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", child.getId(), "description", HISTORY))
                    .isEqualTo(childHistory);
            assertThat(content.childSources(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "lines", child.getId()))
                    .isEqualTo(childSource);
            var aggregateTranslations = new BusinessContentTranslationService(aggregateContent, store, translations, transactions);
            var childLocation = new BusinessContentService.Location(parent.getId(), "lines", child.getId());
            assertThat(aggregateTranslations.read(records.mainEntity(module.moduleAlias()), childLocation, "de").fields().get("description").text())
                    .isEqualTo("Untersuchung ausstehend");
            var childMaintenance = aggregateTranslations.prepare(records.mainEntity(module.moduleAlias()), childLocation,
                    "description", "de", PlatformAction.CONTENT_EDIT);
            aggregateTranslations.saveDraft(records.mainEntity(module.moduleAlias()), childLocation, "description", "de",
                    change(childMaintenance, "Neue Untersuchung", null));
            assertThat(aggregateTranslations.read(records.mainEntity(module.moduleAlias()), childLocation, "de").fields().get("description").text())
                    .isEqualTo("Untersuchung ausstehend");
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
            var changedChild = reopened.getChildren("lines").getFirst();
            changedChild.setValue("description", "Investigation revised");
            records.update(module.moduleAlias(), "line", changedChild);
            var childReference = aggregateTranslations.prepare(records.mainEntity(module.moduleAlias()), childLocation,
                    "description", "de", PlatformAction.CONTENT_EDIT);
            assertThat(childReference.original().revisionId()).isNotEqualTo(childOriginal.revisionId());
            assertThat(childReference.previousHuman().original().revisionId()).isEqualTo(childOriginal.revisionId());
            assertThat(childReference.previousHuman().translation().text()).isEqualTo("Neue Untersuchung");
            assertThat(childReference.previousHuman().translation().kind()).isEqualTo("DRAFT");
            assertThat(childReference.draft()).isNull(); assertThat(childReference.confirmed()).isNull();
            assertThat(aggregateTranslations.read(records.mainEntity(module.moduleAlias()), childLocation, "de").fields()
                    .get("description").humanReviewRequired()).isTrue();
            assertThat(aggregateTranslations.read(records.mainEntity(module.moduleAlias()), childLocation, "de").fields()
                    .get("description").text()).isEqualTo("Investigation revised");
            var partial = new DynamicRecord(main).setValue("quantity", 0);
            partial.setId(parent.getId()); partial.setVersion(reopened.getVersion());
            records.update(module.moduleAlias(), "main", partial);
            assertThat(content.history(runtime.entityService(module.moduleAlias(), "main"), parent.getId(), "description", HISTORY)).hasSize(1);
            assertThat(records.select(module.moduleAlias(), "main", parent.getId()).getValue("description")).isEqualTo("80°C，暂勿发货");
            records.delete(module.moduleAlias(), "main", parent.getId());
            assertThat(store.latest(parentSource)).isNotNull();
            assertThat(translations.history(parentSource, parentOriginal, "en", HISTORY)).hasSize(1);
            assertThatThrownBy(() -> aggregateContent.childSources(records.mainEntity(module.moduleAlias()), parent.getId(), "lines", child.getId()))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            // Exercise the retained-resource primitive used after recovery has verified each owner.
            records.delete(module.moduleAlias(), "line", child.getId());
            RetainedRecordPurgeSupport.purge(runtime.entityService(module.moduleAlias(), "line"), child.getId(),
                    records.selectIgnoreSoftDelete(module.moduleAlias(), "line", child.getId()).getVersion());
            RetainedRecordPurgeSupport.purge(runtime.entityService(module.moduleAlias(), "main"), parent.getId(),
                    records.selectIgnoreSoftDelete(module.moduleAlias(), "main", parent.getId()).getVersion());
            assertThat(store.latest(parentSource)).isNull();
            assertThat(store.latest(childTextSource)).isNull();
            assertThat(translations.history(parentSource, parentOriginal, "en", HISTORY)).isEmpty();
            assertThat(translations.history(childTextSource, childOriginal, "de", HISTORY)).isEmpty();
            assertThat(records.select(module.moduleAlias(), "main", another.getId())).isNotNull();
            assertThat(content.history(runtime.entityService(module.moduleAlias(), "line"), otherLine.getId(), "description", HISTORY)).hasSize(1);
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
    @Test void multilingualSearchUsesOnlyTheCurrentReadableTranslationPerLanguage() {
        String key = "query-" + UUID.randomUUID();
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("数控设备 " + key); service.insert(row);
            var source = new BusinessContentSource("tenant-a", "test.content", "content_fixture", row.getId(), "category-name");
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "Machine " + key, null, generation()));
            assertSearch(row, "Machine " + key, true);
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Human " + key, 1));
            assertSearch(row, "Human " + key, false);
            assertSearch(row, "Machine " + key, true);
            tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 2, 2));
            assertSearch(row, "Machine " + key, false);
            assertSearch(row, "Human " + key, true);
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "Refresh " + key, 3, generation()));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Private " + key, 4));
            assertSearch(row, "Human " + key, true);
            assertSearch(row, "Private " + key, false);
            assertSearch(row, "Refresh " + key, false);
            tx.executeWithoutResult(status -> translations.remove(source, original, "en", 5));
            assertSearch(row, "Human " + key, false);
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "New draft " + key, 6));
            assertSearch(row, "Human " + key, false);
            assertSearch(row, "New draft " + key, false);
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "New machine " + key, 7, generation()));
            assertSearch(row, "New machine " + key, true);
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "de", "Maschine " + key, null));
            tx.executeWithoutResult(status -> translations.confirm(source, original, "de", 1, 1));
            assertSearch(row, "Maschine " + key, true);
            assertThat(service.count(queries.expandUserText(service, Criteria.of().likeIgnoreCase("title", "%" + key + "%"))))
                    .isEqualTo(1); // Three languages still identify one business record.
        }
    }

    @Test void multilingualSearchRejectsStaleOriginalsReusedRevisionNumbersAndUntrackedWrites() {
        String key = "query-" + UUID.randomUUID();
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("初始原文 " + key);
            try (var input = input(row, "zh-CN", null)) { service.insert(row); }
            var source = new BusinessContentSource("tenant-a", "test.content", "content_fixture", row.getId(), "category-name");
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            var old = tx.execute(status -> translations.saveMachine(source, original, "en", "Old " + key, null, generation()));
            assertSearch(row, "Old " + key, true);
            row.setTitle("New original " + key);
            try (var input = input(row, "en-US", 1)) { service.update(row); }
            var current = BusinessContentStore.OriginalVersion.of(store.latest(source));
            assertSearch(row, "Old " + key, false);
            tx.executeWithoutResult(status -> translations.saveMachine(source, current, "en", "Same language " + key, null, generation()));
            assertSearch(row, "Same language " + key, false); // Reading en uses the en-US original.
            tx.executeWithoutResult(status -> translations.saveMachine(source, current, "de", "Aktuell " + key, null, generation()));
            assertSearch(row, "Aktuell " + key, true);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("update content_fixture set title = ? where id = ?", "初始原文 " + key, row.getId());
            assertSearch(row, "Old " + key, false); // An older matching raw text cannot become the latest original.
            assertSearch(row, "Aktuell " + key, false);
            jdbc.update("update content_fixture set title = ? where id = ?", row.getTitle(), row.getId());
            old.setId(UUID.randomUUID().toString().replace("-", "")); old.setSourceRevision(current.revision()); old.setLanguageTag("de"); old.setRevision(99);
            old.setTranslatedText("Wrong immutable original " + key); // Same ordinal, different original ID.
            translationDao.insert(old);
            assertSearch(row, "Wrong immutable original " + key, false);
            assertSearch(row, "Aktuell " + key, true);
        }
    }

    @Test void multilingualSearchPreservesNestedScopeNegativeFiltersLiteralWildcardsAndServerPages() {
        String key = "query-" + UUID.randomUUID();
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            List<ContentFixture> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                var row = row("原文 " + i + " " + key); row.setQuantity(i); service.insert(row); rows.add(row);
                var source = new BusinessContentSource("tenant-a", "test.content", "content_fixture", row.getId(), "category-name");
                var version = BusinessContentStore.OriginalVersion.of(store.latest(source));
                int index = i;
                tx.executeWithoutResult(status -> translations.saveMachine(source, version, "en", "CNC " + index + " %_ " + key, null, generation()));
            }
            var controls = queries.expandUserText(service, Criteria.of().andGroup(group -> group
                    .likeIgnoreCase("title", "%CNC%" + key + "%").orEq("title", rows.get(2).getTitle())));
            var scoped = Criteria.copyOf(controls).lt("quantity", 2);
            assertThat(service.count(scoped)).isEqualTo(2);
            var first = service.pageQuery(scoped, PageRequest.of(1, 1), Sort.asc("quantity"), Sort.asc("id"));
            var second = service.pageQuery(scoped, PageRequest.of(2, 1), Sort.asc("quantity"), Sort.asc("id"));
            assertThat(first.getTotal()).isEqualTo(2); assertThat(second.getTotal()).isEqualTo(2);
            assertThat(first.getRecords()).extracting(ContentFixture::getId).containsExactly(rows.get(0).getId());
            assertThat(second.getRecords()).extracting(ContentFixture::getId).containsExactly(rows.get(1).getId());
            var excluded = queries.expandUserText(service, Criteria.of().ne("title", "CNC 0 %_ " + key)
                    .notIn("title", List.of("CNC 1 %_ " + key)));
            assertThat(service.list(Criteria.copyOf(excluded).in("id", rows.stream().map(ContentFixture::getId).toList())))
                    .extracting(ContentFixture::getId).containsExactly(rows.get(2).getId());
            var literal = queries.expandUserText(service, Criteria.of().likeIgnoreCase("title",
                    net.ximatai.muyun.spring.ability.query.QueryLikePattern.containsLiteral("%_ " + key)));
            assertThat(service.count(literal)).isEqualTo(3);
            var impossibleLiteral = queries.expandUserText(service, Criteria.of().likeIgnoreCase("title",
                    net.ximatai.muyun.spring.ability.query.QueryLikePattern.containsLiteral("X%_ " + key)));
            assertThat(service.count(impossibleLiteral)).isZero();
            service.delete(rows.get(0).getId());
            assertThat(service.count(scoped)).isEqualTo(1);
            try (var other = TenantContext.use("tenant-b")) { assertThat(service.count(controls)).isZero(); }
        }
    }

    @Test void multilingualDynamicMainAndChildUseTheirDeclaredIdentityAndCurrentPhysicalField() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String alias = "test.content_query_" + suffix;
        var main = new EntityDefinition("main", "content_query_main_" + suffix, "Main", List.of(
                FieldDefinition.text("description", "Description").content(new ContentFieldDefinition("stable-description", ContentPolicy.AUTO_TRANSLATE))));
        var child = new EntityDefinition("line", "content_query_line_" + suffix, "Line", List.of(
                FieldDefinition.text("description", "Description").content(new ContentFieldDefinition("stable-description", ContentPolicy.MANUAL_TRANSLATIONS))));
        var schema = new DynamicSchemaService(operations); schema.ensureTable(main); schema.ensureTable(child);
        try (var runtime = new DynamicRecordRuntime(operations).register(new ModuleDefinition(alias, "Content query", List.of(main, child)));
             var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var mainService = runtime.entityService(alias, "main"); var childService = runtime.entityService(alias, "line");
            var parent = new DynamicRecord(main).setValue("description", "主表中文"); mainService.insert(parent);
            var line = new DynamicRecord(child).setValue("description", "子表中文"); childService.insert(line);
            var parentSource = new BusinessContentSource("tenant-a", alias, "main", parent.getId(), "stable-description");
            var childSource = new BusinessContentSource("tenant-a", alias, "line", line.getId(), "stable-description");
            tx.executeWithoutResult(status -> translations.saveMachine(parentSource, BusinessContentStore.OriginalVersion.of(store.latest(parentSource)),
                    "en", "Quality exception", null, generation()));
            tx.executeWithoutResult(status -> translations.saveDraft(childSource, BusinessContentStore.OriginalVersion.of(store.latest(childSource)),
                    "de", "Untersuchung", null));
            tx.executeWithoutResult(status -> translations.confirm(childSource, BusinessContentStore.OriginalVersion.of(store.latest(childSource)), "de", 1, 1));
            assertThat(mainService.count(queries.expandUserText(mainService, Criteria.of().eq("description", "Quality exception")))).isEqualTo(1);
            assertThat(childService.count(queries.expandUserText(childService, Criteria.of().eq("description", "Untersuchung")))).isEqualTo(1);
            assertThat(mainService.count(queries.expandUserText(mainService, Criteria.of().eq("description", "Untersuchung")))).isZero();
            var update = new DynamicRecord(child).setValue("description", "Revised investigation");
            update.setId(line.getId()); update.setVersion(line.getVersion()); childService.update(update);
            assertThat(childService.count(queries.expandUserText(childService, Criteria.of().eq("description", "Untersuchung")))).isZero();
        }
    }

    @Test void readingQueryProjectionAndCoverageFollowCurrentSourceAndEffectiveTranslation() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("温控设备 " + UUID.randomUUID());
            try (var input = input(row, "zh-CN", null)) { service.insert(row); }
            var source = new BusinessContentSource("tenant-a", "test.content", "content_fixture", row.getId(), "category-name");
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            assertMissing(row, "en", true);
            assertThat(projectedReading(row, "en")).isNull();
            tx.executeWithoutResult(status -> translations.saveMachine(source, original, "en", "Machine equipment", null, generation()));
            assertMissing(row, "en", false); assertThat(projectedReading(row, "en")).isEqualTo("Machine equipment");
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Human equipment", 1));
            assertMissing(row, "en", false); assertThat(projectedReading(row, "en")).isEqualTo("Machine equipment");
            tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 2, 2));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Private draft", 3));
            assertMissing(row, "en", false); assertThat(projectedReading(row, "en")).isEqualTo("Human equipment");
            tx.executeWithoutResult(status -> translations.remove(source, original, "en", 4));
            assertMissing(row, "en", true); assertThat(projectedReading(row, "en")).isNull();
            assertMissing(row, "zh-CN", false); assertThat(projectedReading(row, "zh-CN")).isNull(); // Same-language sorting falls back to raw.
            row.setTitle("Equipment original");
            try (var input = input(row, "en-US", 1)) { service.update(row); }
            assertMissing(row, "en", false); assertMissing(row, "de", true);
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("update content_fixture set title = ? where id = ?", "Untracked change", row.getId());
            assertMissing(row, "en", true); // Matching source language alone cannot validate a stale raw value.
            var empty = row(null); service.insert(empty);
            var emptySource = source(empty);
            var emptyOriginal = BusinessContentStore.OriginalVersion.of(store.latest(emptySource));
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> translations.saveDraft(
                    emptySource, emptyOriginal, "en", "Human empty-original reading", null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nonempty original");
            assertThat(translationService.read(service, BusinessContentService.Location.record(empty.getId()), "en")
                    .fields().get("title").text()).isNull();
            assertThat(projectedReading(empty, "en")).isNull();
            assertSearch(empty, "Human empty-original reading", false);
            assertMissing(empty, "en", false);
            assertThat(service.select(empty.getId()).getTitle()).isNull();
        }
    }

    @Test void readingCoverageDoesNotHideUntranslatedRowsBehindAnOrTextFilterOrCountBlankFields() {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var low = row("未翻译设备 " + UUID.randomUUID()); low.setQuantity(1); service.insert(low);
            var high = row("未翻译设备 " + UUID.randomUUID()); high.setQuantity(7); service.insert(high);
            var userQuery = Criteria.of().andGroup(group -> group.eq("title", "English equipment").orEq("quantity", 7));
            var actual = queries.expandUserText(service, userQuery);
            var coverage = queries.coverageUserScope(service, userQuery);
            var identities = List.of(low.getId(), high.getId());
            assertThat(service.count(Criteria.copyOf(actual).in("id", identities))).isEqualTo(1);
            assertThat(service.count(Criteria.copyOf(coverage).in("id", identities))).isEqualTo(2);
            assertThat(service.count(Criteria.copyOf(coverage).in("id", identities).lt("quantity", 2))).isEqualTo(1);
            for (String blank : new String[]{null, "", "   ", "\t\n", "\u2003", "\u3000\r\u001c"}) {
                var row = row(blank); service.insert(row); assertMissing(row, "en", false);
            }
            for (String nonblank : List.of("\u00a0", "\u2007", "\u202f", "\tA\n")) {
                assertThat(nonblank.isBlank()).isFalse();
                var row = row(nonblank); service.insert(row); assertMissing(row, "en", true);
            }
            assertThat(service.count(queries.missingReadingCriteria(service, Set.of("quantity"), "en"))).isZero();
            try (var other = TenantContext.use("tenant-b")) {
                assertThat(service.count(Criteria.copyOf(coverage).in("id", identities))).isZero();
            }
        }
    }

    @Test void readingHeadUsesBoundedIndexLookupsDespiteGrowingMachineHistory() throws Exception {
        try (var user = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var row = row("历史增长设备 " + UUID.randomUUID()); service.insert(row);
            var source = source(row);
            var original = BusinessContentStore.OriginalVersion.of(store.latest(source));
            tx.executeWithoutResult(status -> translations.saveDraft(source, original, "en", "Confirmed equipment", null));
            tx.executeWithoutResult(status -> translations.confirm(source, original, "en", 1, 1));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("""
                    insert into platform_business_content_translation
                        (id, source_key, source_revision_id, source_revision, language_tag, revision, kind,
                         translated_text, tenant_id, module_alias, entity_key, record_id, field_key)
                    select md5(concat(t.id, '-', n)), t.source_key, t.source_revision_id, t.source_revision,
                           t.language_tag, n + 2, 'MACHINE', 'Later machine equipment', t.tenant_id,
                           t.module_alias, t.entity_key, t.record_id, t.field_key
                    from platform_business_content_translation t cross join generate_series(1, 20000) n
                    where t.source_revision_id = ? and t.kind = 'CONFIRMED'
                    """, original.revisionId());
            jdbc.execute("analyze platform_business_content_translation");
            assertThat(projectedReading(row, "en")).isEqualTo("Confirmed equipment");
            assertMissing(row, "en", false);
            var projection = queries.readingProjection(service, "title", "en", "history_plan_");
            var params = new LinkedHashMap<String, Object>(projection.getParams()); params.put("record", row.getId());
            var plan = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource).queryForObject(
                    "explain (analyze, format json) select reading_text from (" + projection.getSql()
                            + ") reading where record_id = :record", params, String.class);
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan);
            var scans = tree.findParents("Relation Name").stream()
                    .filter(node -> "platform_business_content_translation".equals(node.path("Relation Name").asText())).toList();
            assertThat(scans).isNotEmpty();
            for (var scan : scans) {
                assertThat(scan.path("Actual Rows").asDouble() * scan.path("Actual Loops").asDouble())
                        .as("Translation history scan: %s", scan).isLessThan(32);
                assertThat(scan.path("Rows Removed by Filter").asDouble()).isLessThan(32);
            }
        }
    }

    private void assertMissing(ContentFixture row, String language, boolean missing) {
        var criterion = queries.missingReadingCriteria(service, Set.of("title"), language);
        assertThat(service.count(Criteria.copyOf(criterion).eq("id", row.getId()))).isEqualTo(missing ? 1 : 0);
    }

    private String projectedReading(ContentFixture row, String language) {
        var projection = queries.readingProjection(service, "title", language, "reading_test_");
        Map<String, Object> params = new LinkedHashMap<>(projection.getParams()); params.put("record", row.getId());
        var values = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource)
                .queryForList("select reading_text from (" + projection.getSql() + ") reading where record_id = :record", params);
        return values.isEmpty() ? null : (String) values.getFirst().get("reading_text");
    }

    private void assertSearch(ContentFixture row, String value, boolean matched) {
        Criteria criteria = queries.expandUserText(service, Criteria.of().eq("title", value));
        assertThat(service.count(Criteria.copyOf(criteria).eq("id", row.getId()))).isEqualTo(matched ? 1 : 0);
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

    @Test void aggregateFieldPolicyUsesTheChildIdentityInsteadOfTheReadableParentIdentity() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var parent = row("可读父记录"); service.insert(parent);
            var childService = new PolicyChildService(dao, Set.of(parent.getId()));
            var child = row("不可读子说明"); child.setParentId(parent.getId()); childService.insert(child);
            var aggregate = new PolicyAggregateService(dao, childService);
            var parentView = ActionExecutionContext.ofPlatformAction(aggregate.getModuleAlias(), PlatformAction.VIEW,
                    Set.of(parent.getId()), CurrentUserContext.currentUser());
            for (var owningView : List.of(parentView, ActionExecutionContext.ofPlatformAction(childService.getModuleAlias(),
                    PlatformAction.VIEW, Set.of(parent.getId()), CurrentUserContext.currentUser()))) {
                try (var context = ActionExecutionContextHolder.use(owningView)) {
                assertThat(content.childSources(aggregate, parent.getId(), "lines", child.getId()).fields()).isEmpty();
                assertThat(childService.lastRead.moduleAlias()).isEqualTo(childService.getModuleAlias());
                assertThat(childService.lastRead.recordIds()).containsExactly(child.getId());
                assertThatThrownBy(() -> content.childHistory(aggregate, parent.getId(), "lines", child.getId(), "title", HISTORY))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                var location = new BusinessContentService.Location(parent.getId(), "lines", child.getId());
                assertThat(translationService.read(aggregate, location, "en").fields()).isEmpty();
                assertThatThrownBy(() -> translationService.prepare(aggregate, location, "title", "en", PlatformAction.CONTENT_EDIT))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                assertThat(ActionExecutionContextHolder.current()).contains(owningView);
                }
            }
            childService.readableIds.add(child.getId());
            try (var context = ActionExecutionContextHolder.use(parentView)) {
                assertThat(content.childSources(aggregate, parent.getId(), "lines", child.getId()).fields().get("title").text())
                        .isEqualTo("不可读子说明");
                assertThat(content.childHistory(aggregate, parent.getId(), "lines", child.getId(), "title", HISTORY))
                        .extracting(BusinessContentService.SourceRevision::text).containsExactly("不可读子说明");
            }
        }
    }

    @Test void aggregateContentSelectionIsBoundedAndRetainsTheActualOwningForeignKeyAndTenant() {
        try (var actor = actor("cn"); var tenant = TenantContext.use("tenant-a")) {
            var parent = row("选择范围"); service.insert(parent);
            var childService = new PolicyChildService(dao, Set.of());
            var aggregate = new PolicyAggregateService(dao, childService);
            var children = new ArrayList<String>();
            for (int i = 0; i < 2; i++) {
                var child = row("明细" + i); child.setParentId(parent.getId()); childService.insert(child); children.add(child.getId());
            }
            var foreign = row("其他父记录明细"); foreign.setParentId("other-parent"); childService.insert(foreign);
            var limited = content.childSelection(aggregate, parent.getId(), "lines", 1);
            assertThat(limited.ids()).hasSize(1).allMatch(children::contains); assertThat(limited.more()).isTrue();
            var complete = content.childSelection(aggregate, parent.getId(), "lines", 2);
            assertThat(complete.ids()).containsExactlyInAnyOrderElementsOf(children); assertThat(complete.more()).isFalse();
            assertThat(content.childSelection(aggregate, parent.getId(), "lines", 0).more()).isTrue();
            try (var other = CurrentUserContext.use(CurrentUser.tenantUser("other", "Other", "tenant-b"));
                 var otherTenant = TenantContext.use("tenant-b")) {
                assertThatThrownBy(() -> content.childSelection(aggregate, parent.getId(), "lines", 10))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
        }
    }

    static class PolicyChildService extends AbstractAbilityService<ContentFixture>
            implements net.ximatai.muyun.spring.ability.child.ChildAbility<ContentFixture>, FieldReadAbility {
        final Set<String> readableIds; ActionExecutionContext lastRead;
        PolicyChildService(ContentFixtureDao dao, Set<String> readableIds) {
            super("test.content_line", ContentFixture.class, dao); this.readableIds = new HashSet<>(readableIds);
        }
        @Override public FieldReadPolicy fieldReadPolicy(ActionExecutionContext action) {
            lastRead = action;
            return readableIds.containsAll(action.recordIds()) ? FieldReadPolicy.allReadable() : FieldReadPolicy.readableFields(Set.of("id"));
        }
    }
    static class PolicyAggregateService extends FixtureService implements net.ximatai.muyun.spring.ability.child.ChildrenAbility<ContentFixture> {
        final PolicyChildService child;
        PolicyAggregateService(ContentFixtureDao dao, PolicyChildService child) { super(dao); this.child = child; }
        @Override public List<net.ximatai.muyun.spring.ability.child.ChildRelation<?, ContentFixture>> childRelations() {
            return List.of(new net.ximatai.muyun.spring.ability.child.ChildRelation<ContentFixture, ContentFixture>("lines", child,
                    ContentFixture::setParentId, "parentId", ignored -> List.of(), ContentFixture::getParentId));
        }
    }
    static class RetainedFixtureService extends FixtureService implements RecycleBinAbility<ContentFixture> {
        RetainedFixtureService(ContentFixtureDao dao) { super(dao); }
        @Override public boolean isRecycleBinPurgeEnabled() { return true; }
        @Override public void beforeRecycleBinPurge(String id) { }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = BusinessContentRevisionDao.class)
    @Import({BusinessContentStore.class, BusinessContentTranslationStore.class, BusinessContentService.class, BusinessContentTranslationService.class, BusinessContentQueryService.class,
            BusinessContentSaveLifecycleListener.class, BusinessContentDeletionObserver.class, BusinessContentGenerationStore.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ActionExecutionPolicyService policies() { return new AllowAllActionExecutionPolicyService(); }
        @Bean DataScopeCriteriaService scopes() { return new AllowAllDataScopeCriteriaService(); }
    }
}
