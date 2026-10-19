package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.platform.code.fixture.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.ability.deletion.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = StaticCodeRepositoryIT.Configuration.class)
class StaticCodeRepositoryIT extends PlatformPostgresIntegrationTest {
    static final String MODULE = "contract.static_code";
    @Autowired FixtureService service;
    @Autowired CodeRuleService rules;
    @Autowired CodeRuntimeFacade codes;
    @Autowired CodeLedgerEntryService ledger;
    @Autowired CodeRecycleEntryService recycle;
    @Autowired CodeSaveLifecycleListener listener;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    private TransactionTemplate tx;
    @BeforeEach void install() {
        CodeTestMutationHost.install(transactions, source); tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener);
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new CompositeDeletionLifecycleListener(DeletionLifecycleListener.NONE,
                List.of(new CodeDeletionObserver(new CodeOccupationService(ledger, recycle)))));
    }
    @AfterEach void reset() {
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener(); PlatformAbilityRuntime.resetDeletionLifecycleListener();
        PlatformAbilityRuntime.resetMutationTransactionOperator();
    }
    @Test void staticServiceNeedsNoDynamicMetadataOrPageForAutomaticAndExplicitCoding() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            saveRule("documentNo", "DOC-"); saveRule("confirmationNo", "CONF-");
            var record = row("静态API"); service.insert(record);
            assertThat(record.getDocumentNo()).isEqualTo("DOC-0001");
            assertThat(record.getConfirmationNo()).isNull();
            tx.executeWithoutResult(status -> {
                var issued = codes.issueAndBind(command(record));
                assertThat(codes.issueAndBind(command(record)).value()).isEqualTo(issued.value());
                record.setConfirmationNo(issued.value()); service.update(record);
            });
            assertThat(service.select(record.getId()).getConfirmationNo()).isEqualTo("CONF-0001");
            assertThat(ledger.activeBindings(MODULE, "static_code", record.getId())).hasSize(2);
        }
    }
    @Test void failedFinalValidationRollsBackIssuanceAndLedger() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            saveRule("documentNo", "DOC-");
            var draft = row(null);
            assertThatThrownBy(() -> service.insert(draft)).isInstanceOf(RuntimeException.class);
            assertThat(draft.getDocumentNo()).isNull();
            draft.setSubject("有效"); service.insert(draft);
            assertThat(draft.getDocumentNo()).isEqualTo("DOC-0001");
        }
    }
    @Test void softDeletionRetainsOwnershipAndHardRemovalReleasesOriginalPolicyEvenWhenDisabled() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-");
            var record = row("保留"); service.insert(record); service.delete(record.getId());
            assertThat(ledger.activeBindings(MODULE, "static_code", record.getId())).hasSize(1);
            service.restore(record.getId());
            rules.disable(rule.getId());
            service.delete(record.getId());
            service.purge(record.getId());
            assertThat(ledger.activeBindings(MODULE, "static_code", record.getId())).isEmpty();
            assertThat(recycle.selectByRuleId(rule.getId(), 10)).hasSize(1);
        }
    }
    @Test void restorationRejectsMissingOrStolenOccupationWithoutChangingTheCode() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-");
            var row = row("恢复校验"); service.insert(row); service.delete(row.getId());
            tx.executeWithoutResult(status -> {
                new CodeOccupationService(ledger, recycle).releaseAll(MODULE, "static_code", row.getId());
                ledger.upsertActiveBinding(rule, row.getDocumentNo(), "default", "default", "another-record");
            });
            assertThatThrownBy(() -> service.restore(row.getId())).hasMessageContaining("restoration");
            assertThat(service.selectIgnoreSoftDelete(row.getId()).getDeleted()).isTrue();
        }
    }
    @Test void concurrentStaticCreatesHaveUniqueCodesAndMatchingOccupations() throws Exception {
        String tenant = UUID.randomUUID().toString();
        try (var scope = TenantContext.use(tenant)) { saveRule("documentNo", "DOC-"); }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                try (var scope = TenantContext.use(tenant)) {
                    var row = row("并发"); service.insert(row);
                    assertThat(ledger.activeBindings(MODULE, "static_code", row.getId())).hasSize(1);
                    return row.getDocumentNo();
                }
            });
            var values = new java.util.HashSet<String>();
            for (var future : executor.invokeAll(tasks)) values.add(future.get());
            assertThat(values).hasSize(8);
        }
    }
    @Test void previewDoesNotAllocateAndExplicitFailureRollsBackWithTheBusinessAction() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            saveRule("documentNo", "DOC-"); saveRule("confirmationNo", "CONF-");
            var row = row("显式事务"); service.insert(row);
            assertThat(codes.preview(command(row)).value()).isEqualTo("CONF-0001");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                assertThat(codes.issueAndBind(command(row)).value()).isEqualTo("CONF-0001");
                throw new IllegalStateException("domain action failed");
            })).hasMessageContaining("domain action failed");
            assertThat(ledger.activeBindings(MODULE, "static_code", row.getId())).hasSize(1);
            tx.executeWithoutResult(status -> assertThat(codes.issueAndBind(command(row)).value()).isEqualTo("CONF-0001"));
        }
    }
    @Test void systemPurgeUsesTheRetainedRecordsTenantRatherThanAnotherTenantsIdenticalCode() {
        String firstTenant = UUID.randomUUID().toString(); String secondTenant = UUID.randomUUID().toString();
        StaticCodeRecord first;
        StaticCodeRecord second;
        try (var scope = TenantContext.use(firstTenant)) {
            saveRule("documentNo", "DOC-"); first = row("甲"); service.insert(first);
        }
        try (var scope = TenantContext.use(secondTenant)) {
            saveRule("documentNo", "DOC-"); second = row("乙"); service.insert(second);
        }
        assertThat(first.getDocumentNo()).isEqualTo(second.getDocumentNo());
        try (var scope = TenantContext.system("contract cleanup")) { service.delete(first.getId()); service.purge(first.getId()); }
        try (var scope = TenantContext.use(firstTenant)) { assertThat(ledger.activeBindings(MODULE, "static_code", first.getId())).isEmpty(); }
        try (var scope = TenantContext.use(secondTenant)) { assertThat(ledger.activeBindings(MODULE, "static_code", second.getId())).hasSize(1); }
    }
    @Test void sequenceResetCannotReissueADiscardedNumberAfterFinalRemoval() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-"); rule.setAllowRecycle(false);
            rule.getSequencePolicy().setMaxValue(1L); rule.getSequencePolicy().setOverflowPolicy(CodeSequenceOverflowPolicy.RESET);
            rules.saveRuleTree(rule);
            var first = row("作废"); service.insert(first); service.delete(first.getId()); service.purge(first.getId());
            assertThatThrownBy(() -> service.insert(row("不可复用"))).hasMessageContaining("already exists");
            assertThat(ledger.findByRuleAndValue(rule.getId(), first.getDocumentNo()).getStatus()).isEqualTo(CodeLedgerStatus.DISCARDED);
        }
    }
    @Test void normalizedGeneratedValueIsTheOccupationValueForSaveAndExplicitApi() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", " DOC-");
            rule.getSegments().getLast().setSeparator(" "); rules.saveRuleTree(rule);
            var row = row("规范化"); service.insert(row);
            assertThat(row.getDocumentNo()).isEqualTo("DOC-0001");
            assertThat(ledger.activeBindings(MODULE, "static_code", row.getId()).getFirst().getCodeValue()).isEqualTo(row.getDocumentNo());
            service.delete(row.getId()); service.restore(row.getId());
            var command = CodeRuntimeCommand.builder(MODULE, "static_code", "documentNo").sourceRecordId("explicit-normalized").build();
            tx.executeWithoutResult(status -> assertThat(codes.issueAndBind(command).value()).isEqualTo("DOC-0002"));
        }
    }
    @Test void explicitTriggerDoesNotRegenerateOnOrdinaryLinkedUpdateAndCannotBeCleared() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            saveRule("documentNo", "DOC-");
            var rule = saveRule("confirmationNo", "CONF-"); rule.setLinkedUpdate(true);
            var subject = new CodeRuleSegment(); subject.setSegmentType(CodeSegmentType.FIELD_VALUE); subject.setSourceRef("subject");
            subject.setSeparator("-");
            rule.setSegments(List.of(subject, rule.getSegments().getLast())); rules.saveRuleTree(rule);
            var row = row("FIRST"); service.insert(row);
            tx.executeWithoutResult(status -> {
                var command = CodeRuntimeCommand.builder(MODULE, "static_code", "confirmationNo")
                        .sourceRecordId(row.getId()).context(Map.of("subject", "FIRST")).build();
                row.setConfirmationNo(codes.issueAndBind(command).value()); service.update(row);
            });
            row.setSubject("SECOND"); service.update(row);
            assertThat(row.getConfirmationNo()).isEqualTo("FIRST-0001");
            row.setConfirmationNo(null);
            assertThatThrownBy(() -> service.update(row)).hasMessageContaining("cannot be cleared");
            assertThat(service.select(row.getId()).getConfirmationNo()).isEqualTo("FIRST-0001");
        }
    }
    private CodeRuntimeCommand command(StaticCodeRecord record) {
        return CodeRuntimeCommand.builder(MODULE, "static_code", "confirmationNo").sourceRecordId(record.getId()).build();
    }
    private StaticCodeRecord row(String subject) { var row = new StaticCodeRecord(); row.setSubject(subject); return row; }
    private CodeRule saveRule(String field, String prefix) {
        var rule = new CodeRule(); rule.setModuleAlias(MODULE); rule.setEntityAlias("static_code");
        rule.setFieldName(field); rule.setAllowRecycle(true);
        var literal = new CodeRuleSegment(); literal.setSegmentType(CodeSegmentType.CONSTANT); literal.setFixedValue(prefix);
        var sequence = new CodeRuleSegment(); sequence.setSegmentType(CodeSegmentType.SEQUENCE); sequence.setLength(4);
        rule.setSegments(List.of(literal, sequence));
        var policy = new CodeSequencePolicy(); policy.setStartValue(1L); policy.setStepValue(1L); policy.setSequenceLength(4);
        rule.setSequencePolicy(policy); return rules.saveRuleTree(rule);
    }
    static class FixtureService extends AbstractAbilityService<StaticCodeRecord> implements CodeAbility<StaticCodeRecord>, RecycleBinAbility<StaticCodeRecord> {
        public boolean isRecycleBinPurgeEnabled() { return true; }
        public void beforeRecycleBinPurge(String id) { }
        FixtureService(StaticCodeDao dao) { super(MODULE, StaticCodeRecord.class, dao); }
    }
    @org.springframework.boot.SpringBootConfiguration
    @Import(DynamicCodeCoordinatorRepositoryIT.TestApplication.class)
    static class Configuration {
        @Bean FixtureService staticCodeService(StaticCodeDao dao) { return new FixtureService(dao); }
        @Bean CodeTargetCatalog codeTargets(ObjectProvider<CrudAbility<?>> services) { return new CodeTargetCatalog(services); }
        @Bean @Primary CodeRuleService staticRules(CodeRuleDao dao, CodeRuleSegmentService segments, CodeSequencePolicyService policies,
                CodeValueMappingService mappings, CodeTargetCatalog targets) {
            return new CodeRuleService(dao, segments, policies, mappings, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(targets));
        }
        @Bean CodeRuntimeFacade codeFacade(CodeGenerateService generation, CodeRuleService rules, CodeLedgerEntryService ledger) {
            return new CodeRuntimeFacade(generation, rules, ledger);
        }
        @Bean CodeSaveLifecycleListener codeLifecycle(CodeRuleService rules, CodeGenerateService generation, CodePreviewService preview,
                CodeSequenceStateService sequences, CodeLedgerEntryService ledger, CodeRecycleEntryService recycle,
                CodeBusinessTimeService time, java.time.Clock clock) {
            return new CodeSaveLifecycleListener(rules, generation, preview, sequences, ledger, recycle, time, clock);
        }
    }
}
