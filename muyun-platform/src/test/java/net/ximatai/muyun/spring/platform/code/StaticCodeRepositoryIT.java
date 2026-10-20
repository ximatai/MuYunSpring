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
    @Autowired LineService lines;
    @Autowired CodeRuleService rules;
    @Autowired CodeConfigurationService configuration;
    @Autowired CodeRuntimeFacade codes;
    @Autowired CodeBusinessFormService forms;
    @Autowired CodeOpsActionService ops;
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
    @Test void staticConfigurationDiscoversTheJavaContractAndRejectsInvalidDraftsWithoutMetadata() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var target = configuration.requireTarget(MODULE, "static_code");
            assertThat(target.source()).isEqualTo("STATIC");
            assertThat(target.relationId()).isNull();
            assertThat(target.fields()).extracting(CodeConfigurationService.Field::fieldName)
                    .containsExactlyInAnyOrder("documentNo", "confirmationNo");
            assertThat(target.fields()).allSatisfy(field -> assertThat(field.metadataFieldId()).isNull());
            var rule = saveRule("documentNo", "DOC-");
            assertThat(rule.getFieldRole()).isEqualTo(CodeFieldRole.PRIMARY);
            var invalid = new CodeRuleSegment(); invalid.setSegmentType(CodeSegmentType.FIELD_VALUE);
            invalid.setSourceRef("unknownBusinessField"); rule.setSegments(List.of(invalid, rule.getSegments().getLast()));
            assertThatThrownBy(() -> rules.saveRuleTree(rule)).hasMessageContaining("source");
            assertThat(rules.viewRuleTree(rule.getId()).getSegments()).hasSize(2);
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
            subject.setSeparator("-"); subject.setLength(32); subject.setTruncatePolicy(CodeTruncatePolicy.RIGHT);
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
    @Test void staticPreviewReadsCurrentSequenceAndRecycleCandidatesWithoutConsumingEither() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-");
            var first = row("first"); service.insert(first);
            var command = CodeRuntimeCommand.builder(MODULE, "static_code", "documentNo").build();
            assertThat(codes.preview(command).value()).isEqualTo("DOC-0002");
            assertThat(codes.preview(command).value()).isEqualTo("DOC-0002");
            var second = row("second"); service.insert(second); assertThat(second.getDocumentNo()).isEqualTo("DOC-0002");
            service.delete(first.getId()); service.purge(first.getId());
            assertThat(codes.preview(command).value()).isEqualTo("DOC-0001");
            assertThat(codes.preview(command).value()).isEqualTo("DOC-0001");
            assertThat(recycle.selectByRuleId(rule.getId(), 10).getFirst().getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
            var third = row("third"); service.insert(third); assertThat(third.getDocumentNo()).isEqualTo("DOC-0001");
        }
    }
    @Test void staticAggregateChildrenUseFinalParentCodesOwnSameNamedFieldsAndPreviousParentFactsForLinkedUpdates() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            aggregateRules();
            var line = new StaticCodeLine(); line.setSubject("CHILD");
            var parent = row("ROOT"); parent.setLines(List.of(line)); service.insert(parent);
            assertThat(parent.getDocumentNo()).isEqualTo("ROOT-0001");
            assertThat(line.getLineNo()).isEqualTo("ROOT-0001-CHILD-01");
            parent.setSubject("NEXT"); service.update(parent);
            assertThat(parent.getDocumentNo()).isEqualTo("NEXT-0002");
            assertThat(lines.select(line.getId()).getLineNo()).isEqualTo("NEXT-0002-CHILD-02");
            assertThat(ledger.activeBindings(LineService.MODULE, "static_code_line", line.getId())).singleElement()
                    .extracting(CodeLedgerEntry::getCodeValue).isEqualTo("NEXT-0002-CHILD-02");
            assertThat(configuration.requireTarget(LineService.MODULE, "static_code_line").sources())
                    .extracting(CodeConfigurationService.Source::fieldName).contains("subject", "static_code.subject", "static_code.documentNo");
        }
    }
    @Test void aFailedChildRollsBackBothCodesAndTheSameAggregateDraftCanBeRetried() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            aggregateRules();
            var line = new StaticCodeLine(); var parent = row("RETRY"); parent.setLines(List.of(line));
            assertThatThrownBy(() -> service.insert(parent)).isInstanceOf(RuntimeException.class);
            assertThat(parent.getDocumentNo()).isNull(); assertThat(line.getLineNo()).isNull();
            line.setSubject("CHILD"); service.insert(parent);
            assertThat(parent.getDocumentNo()).isEqualTo("RETRY-0001");
            assertThat(line.getLineNo()).isEqualTo("RETRY-0001-CHILD-01");
        }
    }
    @Test void formPoliciesUseInstalledStaticDeclarationsAndKeepCandidatesOutOfDraftsAndLedger() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            aggregateRules();
            var parent = new LinkedHashMap<String, Object>(); parent.put("subject", "ROOT");
            var draft = new LinkedHashMap<String, Object>(); draft.put("subject", "CHILD");
            var main = forms.fields(MODULE, new CodeBusinessFormRequest(null, parent, null));
            assertThat(main).filteredOn(state -> state.fieldName().equals("documentNo")).singleElement()
                    .satisfies(state -> { assertThat(state.preview()).isEqualTo("ROOT-0001"); assertThat(state.readOnly()).isTrue(); });
            assertThat(main).filteredOn(state -> state.fieldName().equals("confirmationNo")).singleElement()
                    .satisfies(state -> { assertThat(state.configured()).isFalse(); assertThat(state.trigger()).isEqualTo(CodeTrigger.EXPLICIT); });
            var child = forms.fields(MODULE, new CodeBusinessFormRequest("lines", draft, parent));
            assertThat(child).singleElement().extracting(CodeBusinessFieldState::preview).isEqualTo("ROOT-0001-CHILD-01");
            assertThat(parent).containsExactlyEntriesOf(Map.of("subject", "ROOT"));
            assertThat(draft).containsExactlyEntriesOf(Map.of("subject", "CHILD"));
            assertThatThrownBy(() -> forms.fields(MODULE, new CodeBusinessFormRequest("arbitrary", draft, parent)))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class);
            var line = new StaticCodeLine(); line.setSubject("CHILD"); var row = row("ROOT"); row.setLines(List.of(line)); service.insert(row);
            assertThat(row.getDocumentNo()).isEqualTo("ROOT-0001"); assertThat(line.getLineNo()).isEqualTo("ROOT-0001-CHILD-01");
        }
    }
    @Test void formPolicyStillResolvesWhenSourcesAreIncompleteAndReflectsManualAndExplicitModes() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            aggregateRules();
            var main = forms.fields(MODULE, new CodeBusinessFormRequest(null, Map.of(), null));
            assertThat(main).filteredOn(state -> state.fieldName().equals("documentNo")).singleElement()
                    .satisfies(state -> { assertThat(state.configured()).isTrue(); assertThat(state.mode()).isEqualTo(CodeMode.AUTO); });
            var rule = rules.resolveRules(new ResolveCodeRuleCommand(MODULE, "static_code", null, null, null, null)).stream()
                    .map(ResolvedCodeRule::rule).filter(value -> "documentNo".equals(value.getFieldName())).findFirst().orElseThrow();
            rule = rules.viewRuleTree(rule.getId()); rule.setMode(CodeMode.MANUAL); rule.setSegments(List.of()); rule.setSequencePolicy(null); rules.saveRuleTree(rule);
            saveRule("confirmationNo", "CONF-");
            var states = forms.fields(MODULE, new CodeBusinessFormRequest(null, Map.of(), null));
            assertThat(states).filteredOn(state -> state.fieldName().equals("documentNo")).singleElement()
                    .satisfies(state -> { assertThat(state.mode()).isEqualTo(CodeMode.MANUAL); assertThat(state.readOnly()).isFalse(); assertThat(state.preview()).isNull(); });
            assertThat(states).filteredOn(state -> state.fieldName().equals("confirmationNo")).singleElement()
                    .satisfies(state -> { assertThat(state.configured()).isTrue(); assertThat(state.readOnly()).isTrue(); assertThat(state.preview()).isNull(); });
        }
    }
    @Test void formSelectorUsesTheStaticBusinessOrganizationContractRatherThanTheActorsAssignment() {
        String tenant = UUID.randomUUID().toString();
        try (var scope = TenantContext.use(tenant);
             var actor = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                     net.ximatai.muyun.spring.common.identity.CurrentUser.tenantUser("operator", "Operator", tenant, "actor-org"))) {
            saveRule("documentNo", "GLOBAL-");
            var scoped = new CodeRule(); scoped.setModuleAlias(MODULE); scoped.setEntityAlias("static_code"); scoped.setFieldName("documentNo");
            scoped.setOrgScopeType(CodeOrgScopeType.ORG); scoped.setOrgScopeId("business-org"); scoped.setGlobalDefault(false);
            var text = new CodeRuleSegment(); text.setSegmentType(CodeSegmentType.CONSTANT); text.setFixedValue("ORG-");
            var sequence = new CodeRuleSegment(); sequence.setSegmentType(CodeSegmentType.SEQUENCE); sequence.setLength(4);
            scoped.setSegments(List.of(text, sequence)); var policy = new CodeSequencePolicy(); policy.setSequenceLength(4); scoped.setSequencePolicy(policy);
            rules.saveRuleTree(scoped);
            var states = forms.fields(MODULE, new CodeBusinessFormRequest(null, Map.of("subject", "scoped", "businessOrganizationId", "business-org"), null));
            assertThat(states).filteredOn(state -> state.fieldName().equals("documentNo")).singleElement()
                    .extracting(CodeBusinessFieldState::preview).isEqualTo("ORG-0001");
            var row = row("scoped"); row.setBusinessOrganizationId("business-org"); service.insert(row);
            assertThat(row.getDocumentNo()).isEqualTo("ORG-0001");
            var global = forms.fields(MODULE, new CodeBusinessFormRequest(null, Map.of("subject", "global"), null));
            assertThat(global).filteredOn(state -> state.fieldName().equals("documentNo")).singleElement()
                    .extracting(CodeBusinessFieldState::preview).isEqualTo("GLOBAL-0001");
        }
    }
    @Test void operationsRetainSoftDeletedOwnersAndReleaseUsingOriginalFactsAfterTheRuleIsGone() {
        String tenant = UUID.randomUUID().toString();
        try (var scope = TenantContext.use(tenant)) {
            var rule = saveRule("documentNo", "DOC-"); var row = row("retained"); service.insert(row); service.delete(row.getId());
            var entry = ledger.activeBindings(MODULE, "static_code", row.getId()).getFirst();
            rules.delete(rule.getId());
            assertThat(ops.inspectLedgerEntry(entry.getId()).consistent()).isTrue();
            assertThat(ops.releaseStaleLedgerEntry(entry.getId(), "soft-deleted owner").getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
            new org.springframework.jdbc.core.JdbcTemplate(source).update("delete from code_static_contract where id = ?", row.getId());
            assertThat(ops.inspectLedgerEntry(entry.getId()).reason()).isEqualTo(CodeLedgerInconsistencyReason.RECORD_MISSING);
            ops.releaseStaleLedgerEntry(entry.getId(), "physical orphan");
            assertThat(ledger.select(entry.getId()).getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
            assertThat(recycle.selectByRuleId(rule.getId(), 10)).singleElement().satisfies(value -> {
                assertThat(value.getRecycledValue()).isEqualTo("DOC-0001"); assertThat(value.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
            });
        }
    }
    @Test void governanceChangesRecycleAndOccupationTogetherAndNeverReopensACurrentOwnersNumber() {
        String tenant = UUID.randomUUID().toString(); String ownerEntryId;
        try (var scope = TenantContext.use(tenant)) {
            var rule = saveRule("documentNo", "DOC-"); var row = row("released"); service.insert(row); service.delete(row.getId()); service.purge(row.getId());
            var pool = recycle.selectByRuleId(rule.getId(), 10).getFirst();
            ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.DISCARDED, "retire");
            assertThat(ledger.findByRuleAndValue(rule.getId(), "DOC-0001").getStatus()).isEqualTo(CodeLedgerStatus.DISCARDED);
            ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.AVAILABLE, "reopen originally recyclable");
            assertThat(ledger.findByRuleAndValue(rule.getId(), "DOC-0001").getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
            var owner = row("new owner"); service.insert(owner); assertThat(owner.getDocumentNo()).isEqualTo("DOC-0001");
            assertThatThrownBy(() -> ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.AVAILABLE, "cannot steal"))
                    .hasMessageContaining("inactive occupation");
            assertThat(ledger.activeBindings(MODULE, "static_code", owner.getId())).hasSize(1);
            ownerEntryId = ledger.activeBindings(MODULE, "static_code", owner.getId()).getFirst().getId();
        }
        try (var other = TenantContext.use(UUID.randomUUID().toString())) {
            assertThatThrownBy(() -> ops.inspectLedgerEntry(ownerEntryId)).hasMessageContaining("does not exist");
        }
        try (var system = TenantContext.system("numbering inspection acceptance")) {
            assertThat(ops.inspectLedgerEntry(ownerEntryId).consistent()).isTrue();
        }
    }
    @Test void linkedStaticChangesAreAuditedAsAutomaticRatherThanManualEdits() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            aggregateRules(); var row = row("FIRST"); service.insert(row); var old = row.getDocumentNo();
            row.setSubject("SECOND"); service.update(row);
            assertThat(ledger.findByTargetAndValue(MODULE, "static_code", "documentNo", old).getLastAction())
                    .isEqualTo(CodeLedgerAction.RELEASED_BY_LINKED_UPDATE);
        }
    }

    @Test void systemBaselineAdjustmentTargetsTheRulesTenantAndManualCodeChangesKeepTheirAuditMeaning() {
        String tenant = UUID.randomUUID().toString(); CodeRule rule;
        try (var scope = TenantContext.use(tenant)) { rule = saveRule("documentNo", "DOC-"); }
        try (var system = TenantContext.system("numbering baseline acceptance")) {
            ops.setSequenceBaseline(rule.getId(), null, null, 30L, "tenant-specific baseline");
        }
        try (var scope = TenantContext.use(tenant)) {
            var row = row("manual audit"); service.insert(row); assertThat(row.getDocumentNo()).isEqualTo("DOC-0031");
            var changed = rules.viewRuleTree(rule.getId()); changed.setMode(CodeMode.AUTO_WITH_MANUAL_EDIT); rules.saveRuleTree(changed);
            row.setDocumentNo(" MANUAL-7 "); service.update(row); assertThat(row.getDocumentNo()).isEqualTo("MANUAL-7");
            assertThat(ledger.findByTargetAndValue(MODULE, "static_code", "documentNo", "DOC-0031").getLastAction())
                    .isEqualTo(CodeLedgerAction.RELEASED_BY_MANUAL_EDIT);
            var moving = rules.viewRuleTree(rule.getId()); moving.setFieldName("confirmationNo");
            assertThatThrownBy(() -> rules.saveRuleTree(moving)).hasMessageContaining("cannot move");
        }
    }

    @Test void repairNeverRecyclesANumberStillHeldByAnotherRetainedRecordOrOriginallyNonRecyclable() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-"); var owner = row("drift"); service.insert(owner);
            var entry = ledger.activeBindings(MODULE, "static_code", owner.getId()).getFirst();
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            jdbc.update("update code_static_contract set document_no = 'EXTERNAL' where id = ?", owner.getId());
            jdbc.update("insert into code_static_contract (id, tenant_id, subject, document_no, deleted) values (?, ?, 'retained other', 'DOC-0001', true)",
                    UUID.randomUUID().toString().replace("-", ""), owner.getTenantId());
            assertThat(ops.inspectLedgerEntry(entry.getId()).reason()).isEqualTo(CodeLedgerInconsistencyReason.OCCUPIED_BY_ANOTHER_RECORD);
            assertThatThrownBy(() -> ops.releaseStaleLedgerEntry(entry.getId(), "must retain")).hasMessageContaining("cannot be released");
            assertThat(ledger.select(entry.getId()).getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        }
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DISCARD-"); rule.setAllowRecycle(false); rules.saveRuleTree(rule);
            var row = row("discard forever"); service.insert(row); service.delete(row.getId()); service.purge(row.getId());
            var pool = recycle.selectByRuleId(rule.getId(), 10).getFirst();
            assertThatThrownBy(() -> ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.AVAILABLE, "not authorized by original policy"))
                    .hasMessageContaining("inactive occupation");
            assertThat(ledger.findByRuleAndValue(rule.getId(), "DISCARD-0001").getStatus()).isEqualTo(CodeLedgerStatus.DISCARDED);
        }
    }

    @Test void reopeningRecycleEntriesRejectsRetainedHistoricalOwnersWithoutAnActiveLedger() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var rule = saveRule("documentNo", "DOC-"); var old = row("released"); service.insert(old);
            service.delete(old.getId()); service.purge(old.getId());
            var pool = recycle.selectByRuleId(rule.getId(), 10).getFirst();
            ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.DISCARDED, "retire");
            new org.springframework.jdbc.core.JdbcTemplate(source).update(
                    "insert into code_static_contract (id, tenant_id, subject, document_no, deleted) values (?, ?, 'historical owner', 'DOC-0001', true)",
                    old.getId(), old.getTenantId());
            assertThatThrownBy(() -> ops.adjustRecycleEntry(pool.getId(), CodeRecycleStatus.AVAILABLE, "must not reopen"))
                    .hasMessageContaining("retained business record");
            assertThat(recycle.select(pool.getId()).getStatus()).isEqualTo(CodeRecycleStatus.DISCARDED);
            assertThat(ledger.findByRuleAndValue(rule.getId(), "DOC-0001").getStatus()).isEqualTo(CodeLedgerStatus.DISCARDED);
            var next = row("unblocked"); service.insert(next); assertThat(next.getDocumentNo()).isEqualTo("DOC-0002");
        }
    }

    @Test void acceptingAReleasedCodeManuallyUnderANewRuleConsumesTheOriginalRecycleEntry() {
        try (var scope = TenantContext.use(UUID.randomUUID().toString())) {
            var oldRule = saveRule("documentNo", "DOC-"); var old = row("old owner"); service.insert(old);
            service.delete(old.getId()); service.purge(old.getId());
            var pool = recycle.selectByRuleId(oldRule.getId(), 10).getFirst(); rules.disable(oldRule.getId());
            var newRule = saveRule("documentNo", "DOC-"); newRule.setMode(CodeMode.AUTO_WITH_MANUAL_EDIT); rules.saveRuleTree(newRule);
            var owner = row("manual new owner"); owner.setDocumentNo("DOC-0001"); service.insert(owner);
            assertThat(recycle.select(pool.getId()).getStatus()).isEqualTo(CodeRecycleStatus.USED);
            assertThat(ledger.activeBindings(MODULE, "static_code", owner.getId())).singleElement()
                    .satisfies(entry -> assertThat(entry.getRuleId()).isEqualTo(newRule.getId()));
            var next = row("automatic after manual"); service.insert(next); assertThat(next.getDocumentNo()).isEqualTo("DOC-0002");
        }
    }

    private void aggregateRules() {
        var main = saveRule("documentNo", "DOC-"); main.setLinkedUpdate(true);
        var parentSource = codeSource("subject", 16); parentSource.setSeparator("-");
        main.setSegments(List.of(parentSource, main.getSegments().getLast())); rules.saveRuleTree(main);
        var rule = new CodeRule(); rule.setModuleAlias(LineService.MODULE); rule.setEntityAlias("static_code_line");
        rule.setFieldName("lineNo"); rule.setLinkedUpdate(true);
        var parentCode = codeSource("static_code.documentNo", 32); parentCode.setSeparator("-");
        var subject = codeSource("subject", 8); subject.setSeparator("-");
        var sequence = new CodeRuleSegment(); sequence.setSegmentType(CodeSegmentType.SEQUENCE); sequence.setLength(2);
        rule.setSegments(List.of(parentCode, subject, sequence));
        var policy = new CodeSequencePolicy(); policy.setSequenceLength(2); rule.setSequencePolicy(policy); rules.saveRuleTree(rule);
    }
    private CodeRuleSegment codeSource(String field, int length) {
        var source = new CodeRuleSegment(); source.setSegmentType(CodeSegmentType.FIELD_VALUE); source.setSourceRef(field);
        source.setLength(length); source.setTruncatePolicy(CodeTruncatePolicy.RIGHT); return source;
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
    static class FixtureService extends AbstractAbilityService<StaticCodeRecord> implements CodeAbility<StaticCodeRecord>, RecycleBinAbility<StaticCodeRecord>, net.ximatai.muyun.spring.ability.child.ChildrenAbility<StaticCodeRecord> {
        public boolean isRecycleBinPurgeEnabled() { return true; }
        public void beforeRecycleBinPurge(String id) { }
        @Override public String codeOrganizationId(StaticCodeRecord record) { return record.getBusinessOrganizationId(); }
        @Override public boolean usesAutomaticChildRelations() { return false; }
        private final LineService lines;
        FixtureService(StaticCodeDao dao, LineService lines) { super(MODULE, StaticCodeRecord.class, dao); this.lines = lines; }
        @Override public List<net.ximatai.muyun.spring.ability.child.ChildRelation<? extends net.ximatai.muyun.spring.common.model.contract.EntityContract, StaticCodeRecord>> childRelations() {
            return List.of(new net.ximatai.muyun.spring.ability.child.ChildRelation<>("lines", lines, StaticCodeLine::setParentId, "parentId", StaticCodeRecord::getLines, StaticCodeLine::getParentId));
        }
    }
    static class LineService extends AbstractAbilityService<StaticCodeLine> implements CodeAbility<StaticCodeLine>, net.ximatai.muyun.spring.ability.child.ChildAbility<StaticCodeLine> {
        static final String MODULE = "contract.static_code_line";
        LineService(StaticCodeLineDao dao) { super(MODULE, StaticCodeLine.class, dao); }
    }
    @org.springframework.boot.SpringBootConfiguration
    @Import(DynamicCodeCoordinatorRepositoryIT.TestApplication.class)
    static class Configuration {
        @Bean FixtureService staticCodeService(StaticCodeDao dao, LineService lines) { return new FixtureService(dao, lines); }
        @Bean LineService lineService(StaticCodeLineDao dao) { return new LineService(dao); }
        @Bean CodeTargetCatalog codeTargets(ObjectProvider<CrudAbility<?>> services) { return new CodeTargetCatalog(services); }
        @Bean @Primary CodeRuleService staticRules(CodeRuleDao dao, CodeRuleSegmentService segments, CodeSequencePolicyService policies,
                CodeValueMappingService mappings, CodeTargetCatalog targets, CodeConfigurationService configuration) {
            return new CodeRuleService(dao, segments, policies, mappings, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(targets), Optional.of(configuration));
        }
        @Bean CodeConfigurationService codeConfiguration(CodeTargetCatalog targets) {
            return new CodeConfigurationService(targets,
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.MetadataService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class),
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class));
        }
        @Bean CodeOpsActionService codeOps(CodeRuleService rules, CodeSequenceStateService sequences, CodeIssueLogService logs,
                CodeRecycleEntryService recycle, CodeLedgerEntryService ledger, net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService records) {
            return new CodeOpsActionService(rules, sequences, logs, recycle, ledger, records);
        }
        @Bean CodeBusinessFormService codeForms(CodeTargetCatalog targets, CodeRuleService rules, CodeGenerateService generation, CodeBusinessTimeService time) {
            return new CodeBusinessFormService(targets, Optional.empty(), rules, generation, time, new com.fasterxml.jackson.databind.ObjectMapper());
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
