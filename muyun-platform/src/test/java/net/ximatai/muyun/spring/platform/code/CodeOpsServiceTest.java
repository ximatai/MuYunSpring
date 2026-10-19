package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeOpsServiceTest {
    private final CodeRuleSegmentService segmentService = new CodeRuleSegmentService(new TestMemoryDao<>());
    private final CodeSequencePolicyService policyService = new CodeSequencePolicyService(new TestMemoryDao<>());
    private final CodeValueMappingService mappingService = new CodeValueMappingService(new TestMemoryDao<>());
    private final CodeRuleService ruleService = new CodeRuleService(
            new TestMemoryDao<>(),
            segmentService,
            policyService,
            mappingService
    );
    private final CodeSequenceStateService stateService = new CodeSequenceStateService(new TestMemoryDao<>());
    private final CodeIssueLogService issueLogService = new CodeIssueLogService(new TestMemoryDao<>());
    private final CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
    private final CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>(), recycleService);
    private final DynamicRecordService recordService = mock(DynamicRecordService.class);
    private final CodeOpsQueryService queryService = new CodeOpsQueryService(
            ruleService,
            stateService,
            issueLogService,
            recycleService,
            ledgerService
    );
    private final CodeOpsActionService actionService = new CodeOpsActionService(
            ruleService,
            stateService,
            issueLogService,
            recycleService,
            ledgerService,
            recordService
    );

    @Test
    void shouldLocateAndSetSequenceBaselineWithGovernanceLog() {
        CodeRule rule = rule("orderNo", true);
        ruleService.saveRuleTree(rule);

        CodeSequenceStateLocation missing = queryService.locateSequenceState(rule.getId(), null, null);
        assertThat(missing.found()).isFalse();
        assertThat(missing.nextValue()).isEqualTo(1L);

        CodeSequenceBaselineResult baseline = actionService.setSequenceBaseline(
                rule.getId(),
                null,
                null,
                99L,
                "import"
        );

        assertThat(baseline.beforeValue()).isNull();
        assertThat(baseline.afterValue()).isEqualTo(99L);
        assertThat(baseline.nextValue()).isEqualTo(100L);
        assertThat(stateService.selectState(rule.getId(), null, null).getCurrentValue()).isEqualTo(99L);
        assertThat(issueLogService.selectByRuleId(rule.getId(), 10))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo(CodeIssueLogStatus.SUCCESS);
                    assertThat(log.getMessage()).contains("Set sequence baseline").contains("import");
                });
    }

    @Test
    void shouldAdjustSequenceStateByStateRowThroughBaselineGovernance() {
        CodeRule rule = rule("orderNo", true);
        ruleService.saveRuleTree(rule);
        CodeSequenceState state = stateService.setCurrentValue(rule.getId(), "customer-a", "202606", 12L);

        CodeSequenceBaselineResult adjusted = actionService.adjustSequenceState(state.getId(), 120L, "repair");

        assertThat(adjusted.beforeValue()).isEqualTo(12L);
        assertThat(adjusted.afterValue()).isEqualTo(120L);
        assertThat(adjusted.nextValue()).isEqualTo(121L);
        assertThat(stateService.select(state.getId()).getCurrentValue()).isEqualTo(120L);
        assertThat(issueLogService.selectByRuleId(rule.getId(), 10))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getBasisKey()).isEqualTo("customer-a");
                    assertThat(log.getPeriodKey()).isEqualTo("202606");
                    assertThat(log.getMessage()).contains("Set sequence baseline").contains("repair");
                });
    }

    @Test
    void shouldInspectAndReleaseStaleLedgerEntryWithoutBindingNewValue() {
        CodeRule rule = rule("orderNo", true);
        ruleService.saveRuleTree(rule);
        CodeLedgerEntry active = ledgerService.upsertActiveBinding(rule, "SO-0001",
                CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET, "order-1");
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        DynamicRecord record = new DynamicRecord(entity());
        record.setValue("orderNo", "SO-0002");
        when(recordService.moduleDefinitions()).thenReturn(List.of(new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition("crm.order", "Order", List.of(entity()))));
        when(recordService.entity("crm.order", "main")).thenReturn(operations);
        when(operations.selectIgnoreSoftDelete("order-1")).thenReturn(record);
        when(operations.getDao()).thenReturn(new TestMemoryDao<>());

        CodeLedgerInspection inspection = actionService.inspectLedgerEntry(active.getId());

        assertThat(inspection.consistent()).isFalse();
        assertThat(inspection.reason()).isEqualTo(CodeLedgerInconsistencyReason.VALUE_CHANGED);
        assertThat(inspection.releaseAllowed()).isTrue();

        CodeLedgerEntry released = actionService.releaseStaleLedgerEntry(active.getId(), "manual cleanup");

        assertThat(recycleService.selectByRuleId(rule.getId(), 10)).singleElement().satisfies(entry -> {
            assertThat(entry.getRecycledValue()).isEqualTo("SO-0001"); assertThat(entry.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        });
        assertThat(released.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(released.getSourceRecordId()).isNull();
        assertThat(released.getLastAction()).isEqualTo(CodeLedgerAction.RELEASED_BY_GOVERNANCE);
        assertThat(issueLogService.selectByRuleId(rule.getId(), 10))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getGeneratedValue()).isEqualTo("SO-0001");
                    assertThat(log.getMessage()).contains("Released stale ledger entry").contains("manual cleanup");
                });
    }

    @Test
    void shouldBuildOpsSnapshotForBusinessObjectWithoutRuntimeResolutionFiltering() {
        CodeRule disabled = rule("orderNo", true);
        disabled.setEnabled(Boolean.FALSE);
        ruleService.saveRuleTree(disabled);
        ledgerService.upsertInactiveBinding(disabled, "SO-0001", null, null, null,
                CodeLedgerStatus.AVAILABLE, CodeLedgerAction.RELEASED_BY_DELETE);
        issueLogService.write(disabled, null, null, "SO-0001", CodeIssueLogStatus.SUCCESS, 0, "generated");

        List<CodeRuleOpsSnapshot> snapshots = queryService.queryBusinessObjectSnapshots("crm.order", "main", 5);

        assertThat(snapshots).singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.rule().getId()).isEqualTo(disabled.getId());
                    assertThat(snapshot.issueLogs()).singleElement()
                            .extracting(CodeIssueLog::getGeneratedValue)
                            .isEqualTo("SO-0001");
                    assertThat(snapshot.ledgerEntries()).singleElement()
                            .extracting(CodeLedgerEntry::getStatus)
                            .isEqualTo(CodeLedgerStatus.AVAILABLE);
                });
    }

    @Test void unavailableTargetsAreReportedButNeverInterpretedAsMissingRecords() {
        var rule = rule("orderNo", true); ruleService.saveRuleTree(rule);
        var entry = ledgerService.upsertActiveBinding(rule, "SO-0001", null, null, "missing-module-record");
        var inspection = actionService.inspectLedgerEntry(entry.getId());
        assertThat(inspection.reason()).isEqualTo(CodeLedgerInconsistencyReason.TARGET_UNAVAILABLE);
        assertThat(inspection.releaseAllowed()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actionService.releaseStaleLedgerEntry(entry.getId(), "cannot prove absence"))
                .hasMessageContaining("cannot be released");
        assertThat(ledgerService.select(entry.getId()).getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
    }
    @Test void aStoredRuleCannotMoveItsCountersAndRecyclePoolIntoAnotherBusinessTarget() {
        var rule = rule("orderNo", true); ruleService.saveRuleTree(rule);
        var changed = rule("otherNo", true); changed.setId(rule.getId()); changed.setVersion(rule.getVersion());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ruleService.saveRuleTree(changed)).hasMessageContaining("cannot move");
    }
    @Test void mappingRowsCannotSilentlyDeclareAnUnsupportedIndependentOrganizationScope() {
        var mapping = new CodeValueMapping(); mapping.setSegmentId("segment"); mapping.setSourceValue("a"); mapping.setTargetValue("A");
        mapping.setOrgScopeType(CodeOrgScopeType.ORG); mapping.setOrgScopeId("organization");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mappingService.insert(mapping)).hasMessageContaining("owning rule");
    }

    @Test void retypedHistoricalFieldsAreUnavailableForAutomaticRelease() {
        var rule = rule("orderNo", true); ruleService.saveRuleTree(rule);
        var entry = ledgerService.upsertActiveBinding(rule, "SO-0001", null, null, "historical-row");
        var retyped = new EntityDefinition("main", "crm_order", "Order", List.of(FieldDefinition.integer("orderNo", "Changed type")));
        when(recordService.moduleDefinitions()).thenReturn(List.of(new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition("crm.order", "Order", List.of(retyped))));
        assertThat(actionService.inspectLedgerEntry(entry.getId()).reason()).isEqualTo(CodeLedgerInconsistencyReason.TARGET_UNAVAILABLE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actionService.releaseStaleLedgerEntry(entry.getId(), "cannot confirm old semantics"))
                .hasMessageContaining("cannot be released");
    }

    private EntityDefinition entity() {
        return new EntityDefinition("main", "crm_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no").length(64)
        ));
    }

    private CodeRule rule(String fieldName, boolean allowRecycle) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias("crm.order");
        rule.setEntityAlias("main");
        rule.setFieldName(fieldName);
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
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
        return rule;
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
}
