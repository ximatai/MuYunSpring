package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.JdbiConfigurer;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordActionGateway;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = DynamicCodeCoordinatorRepositoryIT.TestApplication.class)
class DynamicCodeCoordinatorRepositoryIT extends PlatformPostgresIntegrationTest {
    private static final String TENANT_ID = "tenant-code";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.default-schema", () -> "public");
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final DynamicModuleRuntimeRefresher refresher;
    private final DynamicRecordService recordService;
    private final CodeRuleService ruleService;
    private final CodeSequenceStateService stateService;
    private final CodeLedgerEntryService ledgerService;
    private final CodeRecycleEntryService recycleService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    DynamicCodeCoordinatorRepositoryIT(DynamicModuleRuntimeRefresher refresher,
                                       DynamicRecordService recordService,
                                       CodeRuleService ruleService,
                                       CodeSequenceStateService stateService,
                                       CodeLedgerEntryService ledgerService,
                                       CodeRecycleEntryService recycleService,
                                       PlatformTransactionManager transactionManager) {
        this.refresher = refresher;
        this.recordService = recordService;
        this.ruleService = ruleService;
        this.stateService = stateService;
        this.ledgerService = ledgerService;
        this.recycleService = recycleService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void shouldAssignCodeAndLedgerThroughDynamicCreateOnRealRuntime() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);

        DynamicRecord record = create(scenario, "first");

        assertThat(record.getValue("orderNo")).isEqualTo("SO-0001");
        DynamicRecord selected = recordService.select(scenario.moduleAlias(), "main", record.getId());
        assertThat(selected.getValue("orderNo")).isEqualTo("SO-0001");
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(record.getId());
        assertThat(stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET).getCurrentValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectManualValueForAutoAndKeepManualValueForEditableAutoRule() {
        Scenario autoScenario = refreshScenario();
        CodeRule autoRule = saveRule(autoScenario, CodeMode.AUTO, true);

        assertThatThrownBy(() -> create(autoScenario, "invalid", "MANUAL-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("AUTO code field does not accept manual value");
        assertThat(records(autoScenario)).isEmpty();
        assertThat(stateService.selectState(autoRule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET)).isNull();
        assertThat(ledgerEntries(autoRule)).isEmpty();

        Scenario editableScenario = refreshScenario();
        CodeRule editableRule = saveRule(editableScenario, CodeMode.AUTO_WITH_MANUAL_EDIT, true);
        DynamicRecord manual = create(editableScenario, "manual", "KEEP-1");

        assertThat(manual.getValue("orderNo")).isEqualTo("KEEP-1");
        assertThat(stateService.selectState(editableRule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET)).isNull();
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(editableRule.getId(), "KEEP-1");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(manual.getId());
    }

    @Test
    void shouldIgnoreDisabledAndUnmatchedRules() {
        Scenario disabledScenario = refreshScenario();
        CodeRule disabledRule = saveRule(disabledScenario, CodeMode.AUTO, false);
        Scenario unmatchedScenario = refreshScenario();

        DynamicRecord disabled = create(disabledScenario, "disabled");
        DynamicRecord unmatched = create(unmatchedScenario, "unmatched");

        assertThat(disabled.getValue("orderNo")).isNull();
        assertThat(unmatched.getValue("orderNo")).isNull();
        assertThat(stateService.selectState(disabledRule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET)).isNull();
        assertThat(ledgerEntries(disabledRule)).isEmpty();
    }

    @Test
    void shouldGenerateMissingCodeDuringDynamicUpdate() {
        Scenario scenario = refreshScenario();
        DynamicRecord record = create(scenario, "draft");
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);

        DynamicRecord update = recordService.newRecord(scenario.moduleAlias(), "main")
                .setValue("title", "confirmed");
        update.setId(record.getId());
        recordService.update(scenario.moduleAlias(), "main", update);

        assertThat(update.getValue("orderNo")).isEqualTo("SO-0001");
        DynamicRecord selected = recordService.select(scenario.moduleAlias(), "main", record.getId());
        assertThat(selected.getValue("title")).isEqualTo("confirmed");
        assertThat(selected.getValue("orderNo")).isEqualTo("SO-0001");
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(record.getId());
    }

    @Test
    void shouldRollbackDynamicCreateSequenceStateAndLedgerWithOuterTransaction() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);
        AtomicReference<String> recordId = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
                DynamicRecord record = recordService.newRecord(scenario.moduleAlias(), "main")
                        .setValue("title", "rollback");
                recordService.create(scenario.moduleAlias(), "main", record);
                recordId.set(record.getId());

                assertThat(recordService.select(scenario.moduleAlias(), "main", record.getId())).isNotNull();
                assertThat(stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                        CodeSequenceState.DEFAULT_BUCKET)).isNotNull();
                assertThat(ledgerService.findByRuleAndValue(rule.getId(), "SO-0001")).isNotNull();
                status.setRollbackOnly();
            }
        });

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            assertThat(recordService.select(scenario.moduleAlias(), "main", recordId.get())).isNull();
            assertThat(stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                    CodeSequenceState.DEFAULT_BUCKET)).isNull();
            assertThat(ledgerEntries(rule)).isEmpty();
        }
    }

    @Test
    void shouldReleaseDeletedDynamicCodeToAvailableOrDiscardedLedgerFact() {
        Scenario reusableScenario = refreshScenario();
        CodeRule reusableRule = saveRule(reusableScenario, CodeMode.AUTO, true);
        DynamicRecord reusable = create(reusableScenario, "reusable");

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(reusableScenario.moduleAlias(), "main", reusable.getId());
        }

        CodeLedgerEntry reusableLedger = ledgerService.findByRuleAndValue(reusableRule.getId(), "SO-0001");
        assertThat(reusableLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(reusableLedger.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_DELETE);
        assertThat(reusableLedger.getSourceRecordId()).isNull();
        CodeRecycleEntry reusableRecycle = recycleEntry(reusableRule, "SO-0001");
        assertThat(reusableRecycle.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);

        Scenario discardScenario = refreshScenario();
        CodeRule discardRule = saveRule(discardScenario, CodeMode.AUTO, true, false);
        DynamicRecord discarded = create(discardScenario, "discarded");

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(discardScenario.moduleAlias(), "main", discarded.getId());
        }

        CodeLedgerEntry discardLedger = ledgerService.findByRuleAndValue(discardRule.getId(), "SO-0001");
        assertThat(discardLedger.getStatus()).isEqualTo(CodeLedgerStatus.DISCARDED);
        assertThat(discardLedger.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_DELETE);
        assertThat(recycleEntry(discardRule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.DISCARDED);
    }

    @Test
    void shouldReleaseCodesWhenDynamicRecordsAreDeletedInBatch() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);
        DynamicRecord first = create(scenario, "first");
        DynamicRecord second = create(scenario, "second");

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            assertThat(recordService.deleteBatch(scenario.moduleAlias(), "main", List.of(first.getId(), second.getId())))
                    .isEqualTo(2);
        }

        CodeLedgerEntry firstLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(firstLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(firstLedger.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_DELETE);
        assertThat(firstLedger.getSourceRecordId()).isNull();
        assertThat(recycleEntry(rule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);

        CodeLedgerEntry secondLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0002");
        assertThat(secondLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(secondLedger.getSourceRecordId()).isNull();
        assertThat(recycleEntry(rule, "SO-0002").getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
    }

    @Test
    void shouldReleaseOldCodeAndBindManualCodeWhenEditableAutoIsManuallyChanged() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO_WITH_MANUAL_EDIT, true);
        DynamicRecord record = create(scenario, "editable");

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            DynamicRecord update = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("orderNo", "MANUAL-9");
            update.setId(record.getId());
            recordService.update(scenario.moduleAlias(), "main", update);
        }

        assertThat(recordService.select(scenario.moduleAlias(), "main", record.getId()).getValue("orderNo"))
                .isEqualTo("MANUAL-9");
        CodeLedgerEntry oldLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(oldLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(oldLedger.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_MANUAL_EDIT);
        assertThat(recycleEntry(rule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        CodeLedgerEntry newLedger = ledgerService.findByRuleAndValue(rule.getId(), "MANUAL-9");
        assertThat(newLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(newLedger.getSourceRecordId()).isEqualTo(record.getId());
    }

    @Test
    void shouldReleaseOldCodeAndBindRegeneratedCodeWhenLinkedFieldChanges() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveLinkedRule(scenario, true);
        DynamicRecord record = create(scenario, "alpha");

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            DynamicRecord update = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", "beta");
            update.setId(record.getId());
            recordService.update(scenario.moduleAlias(), "main", update);
        }

        DynamicRecord selected = recordService.select(scenario.moduleAlias(), "main", record.getId());
        assertThat(selected.getValue("orderNo")).isEqualTo("SO-beta-0001");
        CodeLedgerEntry oldLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-alpha-0001");
        assertThat(oldLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(oldLedger.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_LINKED_UPDATE);
        assertThat(recycleEntry(rule, "SO-alpha-0001").getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        CodeLedgerEntry newLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-beta-0001");
        assertThat(newLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(newLedger.getSourceRecordId()).isEqualTo(record.getId());
    }

    @Test
    void shouldReuseAvailableRecycleOnceAndNeverConsumeDiscardedCode() {
        Scenario reusableScenario = refreshScenario();
        CodeRule reusableRule = saveRule(reusableScenario, CodeMode.AUTO, true);
        DynamicRecord first = create(reusableScenario, "first");
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(reusableScenario.moduleAlias(), "main", first.getId());
        }

        DynamicRecord reused = create(reusableScenario, "reused");
        DynamicRecord next = create(reusableScenario, "next");

        assertThat(reused.getValue("orderNo")).isEqualTo("SO-0001");
        assertThat(next.getValue("orderNo")).isEqualTo("SO-0002");
        assertThat(recycleEntry(reusableRule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.USED);
        assertThat(ledgerService.findByRuleAndValue(reusableRule.getId(), "SO-0001").getStatus())
                .isEqualTo(CodeLedgerStatus.ACTIVE);

        Scenario discardedScenario = refreshScenario();
        CodeRule discardedRule = saveRule(discardedScenario, CodeMode.AUTO, true, false);
        DynamicRecord discarded = create(discardedScenario, "discarded");
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(discardedScenario.moduleAlias(), "main", discarded.getId());
        }
        DynamicRecord afterDiscard = create(discardedScenario, "after-discard");

        assertThat(recycleEntry(discardedRule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.DISCARDED);
        assertThat(afterDiscard.getValue("orderNo")).isEqualTo("SO-0002");
    }

    @Test
    void shouldRejectDuplicateManualBindingAsSingleCurrentLedgerFact() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO_WITH_MANUAL_EDIT, true);
        DynamicRecord first = create(scenario, "first", "DUP-1");

        assertThatThrownBy(() -> create(scenario, "second", "DUP-1"))
                .isInstanceOf(RuntimeException.class);
        assertThat(ledgerService.findByRuleAndValue(rule.getId(), "DUP-1").getSourceRecordId())
                .isEqualTo(first.getId());
        assertThat(records(scenario)).hasSize(1);
    }

    @Test
    void shouldContinueSequenceAfterAcceptedEditableAutoCode() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO_WITH_MANUAL_EDIT, true);

        DynamicRecord imported = create(scenario, "imported", "SO-0010");
        DynamicRecord next = create(scenario, "next");

        assertThat(imported.getValue("orderNo")).isEqualTo("SO-0010");
        assertThat(next.getValue("orderNo")).isEqualTo("SO-0011");
        CodeSequenceState state = stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET);
        assertThat(state.getCurrentValue()).isEqualTo(11L);
        CodeLedgerEntry importedLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0010");
        assertThat(importedLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(importedLedger.getSourceRecordId()).isEqualTo(imported.getId());
    }

    @Test
    void shouldContinueSequenceAfterAcceptedEditableAutoCodeThroughImportGateway() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO_WITH_MANUAL_EDIT, true);
        AtomicReference<String> importedId = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
                DynamicRecordActionGateway records = recordService.recordsForAction(
                        scenario.moduleAlias(), PlatformAction.IMPORT, "dynamic-import-test");
                DynamicRecord imported = records.newRecord("main")
                        .setValue("title", "imported")
                        .setValue("orderNo", "SO-0010");
                importedId.set(records.create("main", imported));
            }
        });
        DynamicRecord next = create(scenario, "next");

        assertThat(next.getValue("orderNo")).isEqualTo("SO-0011");
        CodeSequenceState state = stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET);
        assertThat(state.getCurrentValue()).isEqualTo(11L);
        CodeLedgerEntry importedLedger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0010");
        assertThat(importedLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(importedLedger.getSourceRecordId()).isEqualTo(importedId.get());
    }

    @Test
    void shouldRollbackRecycleConsumptionWhenDynamicCreateFailsInOuterTransaction() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);
        DynamicRecord first = create(scenario, "first");
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(scenario.moduleAlias(), "main", first.getId());
        }

        transactionTemplate.executeWithoutResult(status -> {
            try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
                DynamicRecord record = recordService.newRecord(scenario.moduleAlias(), "main")
                        .setValue("title", "rollback-reuse");
                recordService.create(scenario.moduleAlias(), "main", record);
                assertThat(record.getValue("orderNo")).isEqualTo("SO-0001");
                assertThat(recycleEntry(rule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.USED);
                status.setRollbackOnly();
            }
        });

        assertThat(recycleEntry(rule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(ledger.getSourceRecordId()).isNull();
    }

    @Test
    void shouldConsumeSingleRecycleCodeOnlyOnceUnderConcurrentDynamicCreates() throws Exception {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveRule(scenario, CodeMode.AUTO, true);
        DynamicRecord first = create(scenario, "first");
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            recordService.delete(scenario.moduleAlias(), "main", first.getId());
        }

        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Callable<String>> tasks = IntStream.range(0, count)
                    .mapToObj(i -> (Callable<String>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
                            DynamicRecord record = recordService.newRecord(scenario.moduleAlias(), "main")
                                    .setValue("title", "concurrent-" + i);
                            recordService.create(scenario.moduleAlias(), "main", record);
                            return String.valueOf(record.getValue("orderNo"));
                        }
                    })
                    .toList();
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> values = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .sorted()
                    .toList();

            assertThat(values).hasSize(count).doesNotHaveDuplicates();
            assertThat(values).contains("SO-0001");
            assertThat(values).containsExactlyElementsOf(IntStream.rangeClosed(1, count)
                    .mapToObj(i -> "SO-%04d".formatted(i))
                    .toList());
            assertThat(recycleEntry(rule, "SO-0001").getStatus()).isEqualTo(CodeRecycleStatus.USED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAssignCodeToDynamicRelationChildOnParentCreate() {
        Scenario scenario = refreshChildScenario();
        CodeRule childRule = saveChildRule(scenario);

        DynamicRecord invoice;
        DynamicRecord line;
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            invoice = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", "invoice-with-line");
            line = recordService.newRecord(scenario.moduleAlias(), "line")
                    .setValue("title", "line-1");
            invoice.setChildren("lines", List.of(line));
            recordService.create(scenario.moduleAlias(), "main", invoice);
        }

        assertThat(invoice.getId()).isNotBlank();
        assertThat(line.getId()).isNotBlank();
        assertThat(line.getValue("mainId")).isEqualTo(invoice.getId());
        assertThat(line.getValue("lineNo")).isEqualTo("LN-0001");

        DynamicRecord selected = recordService.select(scenario.moduleAlias(), "main", invoice.getId());
        DynamicRecord selectedLine = selected.getChildren("lines").getFirst();
        assertThat(selectedLine.getValue("lineNo")).isEqualTo("LN-0001");
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(childRule.getId(), "LN-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(line.getId());
    }

    @Test
    void shouldAllowRelationChildCodeToUseParentContext() {
        Scenario scenario = refreshChildScenario();
        CodeRule childRule = saveChildRuleUsingParentTitle(scenario);

        DynamicRecord invoice;
        DynamicRecord line;
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            invoice = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", "INV");
            line = recordService.newRecord(scenario.moduleAlias(), "line")
                    .setValue("title", "line-1");
            invoice.setChildren("lines", List.of(line));
            recordService.create(scenario.moduleAlias(), "main", invoice);
        }

        assertThat(line.getValue("lineNo")).isEqualTo("LN-INV-0001");
        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(childRule.getId(), "LN-INV-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(line.getId());
    }

    @Test
    void shouldRegenerateRelationChildCodeWhenParentContextDependencyChangesInSameSave() {
        Scenario scenario = refreshChildScenario();
        CodeRule childRule = saveChildRuleUsingParentTitle(scenario);

        DynamicRecord invoice;
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            invoice = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", "INV");
            DynamicRecord line = recordService.newRecord(scenario.moduleAlias(), "line")
                    .setValue("title", "line-1");
            invoice.setChildren("lines", List.of(line));
            recordService.create(scenario.moduleAlias(), "main", invoice);
        }
        DynamicRecord persisted = recordService.select(scenario.moduleAlias(), "main", invoice.getId());
        DynamicRecord persistedLine = persisted.getChildren("lines").getFirst();

        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            DynamicRecord update = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", "INV2");
            update.setId(invoice.getId());
            update.setVersion(persisted.getVersion());
            DynamicRecord lineUpdate = recordService.newRecord(scenario.moduleAlias(), "line");
            lineUpdate.setId(persistedLine.getId());
            lineUpdate.setVersion(persistedLine.getVersion());
            update.setChildren("lines", List.of(lineUpdate));
            recordService.update(scenario.moduleAlias(), "main", update);
        }

        DynamicRecord selected = recordService.select(scenario.moduleAlias(), "main", invoice.getId());
        DynamicRecord selectedLine = selected.getChildren("lines").getFirst();
        assertThat(selectedLine.getValue("lineNo")).isEqualTo("LN-INV2-0001");
        assertThat(ledgerService.findByRuleAndValue(childRule.getId(), "LN-INV-0001").getStatus())
                .isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(ledgerService.findByRuleAndValue(childRule.getId(), "LN-INV2-0001").getStatus())
                .isEqualTo(CodeLedgerStatus.ACTIVE);
    }

    @Test
    void dynamicCodeShouldUseInjectedOrganizationTimeZoneResolver() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveDateRule(scenario);

        DynamicRecord record;
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                    CurrentUser.tenantUser("user-1", "User", TENANT_ID, "org-shanghai"))) {
                record = recordService.newRecord(scenario.moduleAlias(), "main")
                        .setValue("title", "time-zone");
                recordService.create(scenario.moduleAlias(), "main", record);
            }
        }

        assertThat(record.getValue("orderNo")).isEqualTo("SO-20270101-0001");
        assertThat(stateService.selectByRuleId(rule.getId(), 1).getFirst().getPeriodKey()).isEqualTo("20270101");
    }

    @Test
    void shouldFallbackDefaultBucketWhenOldCodeReleaseContextCannotRender() {
        Scenario scenario = refreshScenario();
        CodeRule rule = saveLinkedRule(scenario, true);
        DynamicRecord record = create(scenario, "alpha");
        CodeRule changedRule = ruleService.viewRuleTree(rule.getId());
        changedRule.getSegments().stream()
                .filter(segment -> segment.getSegmentType() == CodeSegmentType.FIELD_VALUE)
                .findFirst()
                .orElseThrow()
                .setSourceRef("missingSource");
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            ruleService.saveRuleTree(changedRule);
            recordService.delete(scenario.moduleAlias(), "main", record.getId());
        }

        CodeLedgerEntry released = ledgerService.findByRuleAndValue(rule.getId(), "SO-alpha-0001");
        assertThat(released.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(released.getBasisKey()).isEqualTo(CodeSequenceState.DEFAULT_BUCKET);
        CodeRecycleEntry recycle = recycleEntry(rule, "SO-alpha-0001");
        assertThat(recycle.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        assertThat(recycle.getBasisKey()).isEqualTo(CodeSequenceState.DEFAULT_BUCKET);
    }

    private DynamicRecord create(Scenario scenario, String title) {
        return create(scenario, title, null);
    }

    private DynamicRecord create(Scenario scenario, String title, String orderNo) {
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            DynamicRecord record = recordService.newRecord(scenario.moduleAlias(), "main")
                    .setValue("title", title);
            if (orderNo != null) {
                record.setValue("orderNo", orderNo);
            }
            recordService.create(scenario.moduleAlias(), "main", record);
            return record;
        }
    }

    private List<DynamicRecord> records(Scenario scenario) {
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return recordService.entity(scenario.moduleAlias(), "main")
                    .list(Criteria.of(), PageRequest.of(1, 20));
        }
    }

    private List<CodeLedgerEntry> ledgerEntries(CodeRule rule) {
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ledgerService.list(Criteria.of().eq("ruleId", rule.getId()), PageRequest.of(1, 20));
        }
    }

    private CodeRecycleEntry recycleEntry(CodeRule rule, String value) {
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return recycleService.list(Criteria.of()
                    .eq("ruleId", rule.getId())
                    .eq("recycledValue", value), PageRequest.of(1, 1))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
    }

    private Scenario refreshScenario() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String moduleAlias = "crm.code_it_" + suffix;
        String tableName = "crm_code_it_" + suffix;
        refresher.refresh(new ModuleDefinition(moduleAlias, "Code IT", List.of(new EntityDefinition(
                "main",
                tableName,
                "Code IT",
                List.of(
                        FieldDefinition.titleField().required(),
                        FieldDefinition.string("orderNo", "Order No").column("order_no").length(64)
                ),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)
        ))));
        return new Scenario(moduleAlias);
    }

    private Scenario refreshChildScenario() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String moduleAlias = "crm.code_child_it_" + suffix;
        String mainTableName = "crm_code_child_main_" + suffix;
        String lineTableName = "crm_code_child_line_" + suffix;
        refresher.refresh(ModuleDefinition.builder(moduleAlias, "Code Child IT")
                .entities(List.of(
                        new EntityDefinition(
                                "main",
                                mainTableName,
                                "Code Child Main",
                                List.of(FieldDefinition.titleField().required()),
                                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)
                        ),
                        new EntityDefinition(
                                "line",
                                lineTableName,
                                "Code Child Line",
                                List.of(
                                        FieldDefinition.string("mainId", "Main").column("main_id").length(64).required(),
                                        FieldDefinition.titleField().required(),
                                        FieldDefinition.string("lineNo", "Line No").column("line_no").length(64)
                                ),
                                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)
                        )
                ))
                .relations(List.of(EntityRelationDefinition.child("lines", "main", "line", "mainId")
                        .withAutoPopulate()
                        ))
                .build());
        return new Scenario(moduleAlias);
    }

    private CodeRule saveRule(Scenario scenario, CodeMode mode, boolean enabled) {
        return saveRule(scenario, mode, enabled, true);
    }

    private CodeRule saveRule(Scenario scenario, CodeMode mode, boolean enabled, boolean allowRecycle) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(scenario.moduleAlias());
        rule.setEntityAlias("main");
        rule.setFieldName("orderNo");
        rule.setTitle("orderNo");
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(mode);
        rule.setEnabled(enabled);
        rule.setAllowRecycle(allowRecycle);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ruleService.saveRuleTree(rule);
        }
    }

    private CodeRule saveLinkedRule(Scenario scenario, boolean allowRecycle) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(scenario.moduleAlias());
        rule.setEntityAlias("main");
        rule.setFieldName("orderNo");
        rule.setTitle("orderNo");
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        rule.setAllowRecycle(allowRecycle);
        rule.setLinkedUpdate(Boolean.TRUE);
        CodeRuleSegment titleBasis = segment(CodeSegmentType.FIELD_VALUE, null, "title");
        titleBasis.setSequenceBasis(Boolean.TRUE);
        titleBasis.setSeparator("-");
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                titleBasis,
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ruleService.saveRuleTree(rule);
        }
    }

    private CodeRule saveChildRule(Scenario scenario) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(scenario.moduleAlias());
        rule.setEntityAlias("line");
        rule.setFieldName("lineNo");
        rule.setTitle("lineNo");
        rule.setFieldRole(CodeFieldRole.NORMAL);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        rule.setAllowRecycle(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "LN-", null),
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ruleService.saveRuleTree(rule);
        }
    }

    private CodeRule saveChildRuleUsingParentTitle(Scenario scenario) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(scenario.moduleAlias());
        rule.setEntityAlias("line");
        rule.setFieldName("lineNo");
        rule.setTitle("lineNo");
        rule.setFieldRole(CodeFieldRole.NORMAL);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        rule.setAllowRecycle(Boolean.TRUE);
        rule.setLinkedUpdate(Boolean.TRUE);
        CodeRuleSegment parentTitle = segment(CodeSegmentType.FIELD_VALUE, null, "main.title");
        parentTitle.setSeparator("-");
        parentTitle.setSequenceBasis(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "LN-", null),
                parentTitle,
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ruleService.saveRuleTree(rule);
        }
    }

    private CodeRule saveDateRule(Scenario scenario) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(scenario.moduleAlias());
        rule.setEntityAlias("main");
        rule.setFieldName("orderNo");
        rule.setTitle("orderNo");
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        CodeRuleSegment day = segment(CodeSegmentType.SYSTEM_TIME, null, null);
        day.setDateFormat(CodeDateFormat.YYYYMMDD);
        day.setSeparator("-");
        day.setSequenceBasis(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                day,
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.DAY);
        rule.setSequencePolicy(policy);
        try (TenantContext.Scope ignored = TenantContext.use(TENANT_ID)) {
            return ruleService.saveRuleTree(rule);
        }
    }

    private CodeRuleSegment sequenceSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SEQUENCE, null, null);
        segment.setLength(4);
        return segment;
    }

    private CodeRuleSegment segment(CodeSegmentType type, String fixedValue, String sourceRef) {
        CodeRuleSegment segment = new CodeRuleSegment();
        segment.setSegmentType(type);
        segment.setFixedValue(fixedValue);
        segment.setSourceRef(sourceRef);
        return segment;
    }

    private record Scenario(String moduleAlias) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @EnableMuYunRepositories(basePackageClasses = CodeRuleDao.class)
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        Clock codeClock() {
            return Clock.fixed(Instant.parse("2026-12-31T16:30:00Z"), ZoneOffset.UTC);
        }

        @Bean
        CodeOrganizationTimeZoneResolver codeOrganizationTimeZoneResolver() {
            return organizationId -> "org-shanghai".equals(organizationId)
                    ? Optional.of(ZoneId.of("Asia/Shanghai"))
                    : Optional.empty();
        }

        @Bean
        CodeBusinessTimeService codeBusinessTimeService(Clock codeClock,
                                                        CodeOrganizationTimeZoneResolver resolver) {
            return new CodeBusinessTimeService(codeClock, List.of(resolver));
        }

        @Bean
        DynamicSchemaService dynamicSchemaService(IDatabaseOperations<?> operations) {
            return new DynamicSchemaService(operations);
        }

        @Bean
        DynamicRecordRuntime dynamicRecordRuntime(IDatabaseOperations<?> operations) {
            return DynamicRecordRuntime.builder(operations)
                    .registry(new DynamicModuleRegistry())
                    .build();
        }

        @Bean
        DynamicModuleRuntimeRefresher dynamicModuleRuntimeRefresher(DynamicSchemaService schemaService,
                                                      DynamicRecordRuntime runtime) {
            return new DynamicModuleRuntimeRefresher(schemaService, runtime);
        }

        @Bean
        DynamicRecordService dynamicRecordService(DynamicRecordRuntime runtime,
                                                  CodeRuleService ruleService,
                                                  CodeGenerateService generateService,
                                                  CodePreviewService previewService,
                                                  CodeSequenceStateService stateService,
                                                  CodeLedgerEntryService ledgerEntryService,
                                                  CodeRecycleEntryService recycleEntryService,
                                                  CodeBusinessTimeService timeService,
                                                  Clock codeClock) {
            DynamicRecordService[] holder = new DynamicRecordService[1];
            DynamicCodeCoordinator coordinator = new DynamicCodeCoordinator(
                    ruleService,
                    generateService,
                    previewService,
                    stateService,
                    ledgerEntryService,
                    recycleEntryService,
                    new DynamicRecordServiceProxy(holder),
                    timeService,
                    codeClock
            );
            holder[0] = new DynamicRecordService(
                    runtime,
                    new AllowAllActionExecutionPolicyService(),
                    new AllowAllDataScopeCriteriaService(),
                    coordinator,
                    codeClock
            );
            return holder[0];
        }

        @Bean
        CodeRuleService codeRuleService(CodeRuleDao ruleDao,
                                        CodeRuleSegmentService segmentService,
                                        CodeSequencePolicyService sequencePolicyService,
                                        CodeValueMappingService mappingService) {
            return new CodeRuleService(ruleDao, segmentService, sequencePolicyService, mappingService);
        }

        @Bean
        CodeRuleSegmentService codeRuleSegmentService(CodeRuleSegmentDao segmentDao) {
            return new CodeRuleSegmentService(segmentDao);
        }

        @Bean
        CodeSequencePolicyService codeSequencePolicyService(CodeSequencePolicyDao policyDao) {
            return new CodeSequencePolicyService(policyDao);
        }

        @Bean
        CodeValueMappingService codeValueMappingService(CodeValueMappingDao mappingDao) {
            return new CodeValueMappingService(mappingDao);
        }

        @Bean
        CodeSequenceAllocator codeSequenceAllocator(Jdbi jdbi) {
            return new PostgresCodeSequenceAllocator(jdbi);
        }

        @Bean
        CodeRecycleConsumer codeRecycleConsumer(Jdbi jdbi) {
            return new PostgresCodeRecycleConsumer(jdbi);
        }

        @Bean
        CodeSequenceStateService codeSequenceStateService(CodeSequenceStateDao stateDao,
                                                          CodeSequenceAllocator sequenceAllocator) {
            return new CodeSequenceStateService(stateDao, List.of(sequenceAllocator));
        }

        @Bean
        CodeLedgerEntryService codeLedgerEntryService(CodeLedgerEntryDao ledgerEntryDao) {
            return new CodeLedgerEntryService(ledgerEntryDao);
        }

        @Bean
        CodeRecycleEntryService codeRecycleEntryService(CodeRecycleEntryDao recycleEntryDao,
                                                        CodeRecycleConsumer recycleConsumer) {
            return new CodeRecycleEntryService(recycleEntryDao, List.of(recycleConsumer));
        }

        @Bean
        CodeIssueLogService codeIssueLogService(CodeIssueLogDao issueLogDao) {
            return new CodeIssueLogService(issueLogDao);
        }

        @Bean
        CodePreviewService codePreviewService(Clock codeClock) {
            return new CodePreviewService(new FormulaEngine(codeClock), codeClock);
        }

        @Bean
        CodeGenerateService codeGenerateService(CodeRuleService ruleService,
                                                CodePreviewService previewService,
                                                CodeSequenceStateService stateService,
                                                CodeRecycleEntryService recycleEntryService,
                                                CodeIssueLogService issueLogService,
                                                Clock codeClock) {
            return new CodeGenerateService(ruleService, previewService, stateService, recycleEntryService,
                    issueLogService, null, codeClock);
        }

        @Bean
        JdbiConfigurer bigIntegerJdbiConfigurer() {
            return jdbi -> jdbi.registerArgument(new AbstractArgumentFactory<BigInteger>(Types.BIGINT) {
                @Override
                protected Argument build(BigInteger value, ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
    }

    private static final class DynamicRecordServiceProxy extends DynamicRecordService {
        private final DynamicRecordService[] delegate;

        private DynamicRecordServiceProxy(DynamicRecordService[] delegate) {
            super(new DynamicRecordRuntime(mock(IDatabaseOperations.class)));
            this.delegate = delegate;
        }

        @Override
        public net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations entity(String moduleAlias,
                                                                                       String entityAlias) {
            return delegate[0].entity(moduleAlias, entityAlias);
        }
    }
}
