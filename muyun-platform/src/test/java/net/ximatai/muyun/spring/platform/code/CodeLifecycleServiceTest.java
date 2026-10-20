package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeLifecycleServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC);
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
    private final CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
    private final CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>(), recycleService);
    private final CodePreviewService previewService = new CodePreviewService(new FormulaEngine(clock), clock);
    private final CodeGenerateService generateService = new CodeGenerateService(
            ruleService,
            previewService,
            stateService,
            recycleService,
            null,
            null,
            clock
    );

    @Test
    void shouldWriteActiveLedgerAndReleaseOldCodeAsAvailableOrDiscarded() {
        CodeRule reusable = rule("orderNo", true);
        ruleService.saveRuleTree(reusable);
        GenerateCodeResult generated = generate("orderNo");
        ledgerService.upsertActiveBinding(reusable, generated.value(), generated.basisKey(), generated.periodKey(), "order-1");

        CodeLedgerEntry active = ledgerService.findByRuleAndValue(reusable.getId(), "SO-0001");
        assertThat(active.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(active.getSourceRecordId()).isEqualTo("order-1");

        recycleService.record(reusable, generated.basisKey(), generated.periodKey(), "SO-0001", "order-1");
        ledgerService.upsertInactiveBinding(reusable, "SO-0001", generated.basisKey(), generated.periodKey(), "order-1",
                CodeLedgerStatus.AVAILABLE, CodeLedgerAction.RELEASED_BY_DELETE);

        CodeRecycleEntry recycled = recycleService.list(null, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
        assertThat(recycled.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        CodeLedgerEntry released = ledgerService.findByRuleAndValue(reusable.getId(), "SO-0001");
        assertThat(released.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(released.getSourceRecordId()).isNull();

        CodeRule discarded = rule("assetNo", false);
        discarded.setFieldRole(CodeFieldRole.NORMAL);
        ruleService.saveRuleTree(discarded);
        recycleService.record(discarded, CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET,
                "AS-0001", "asset-1");

        CodeRecycleEntry discardEntry = recycleService.list(null, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10))
                .stream()
                .filter(entry -> "AS-0001".equals(entry.getRecycledValue()))
                .findFirst()
                .orElseThrow();
        assertThat(discardEntry.getStatus()).isEqualTo(CodeRecycleStatus.DISCARDED);
    }

    @Test
    void shouldPreferAvailableRecycleCodeBeforeAdvancingSequence() {
        CodeRule rule = rule("orderNo", true);
        ruleService.saveRuleTree(rule);
        recycleService.record(rule, CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET,
                "SO-0009", "old-order");

        GenerateCodeResult reused = generate("orderNo");

        assertThat(reused.value()).isEqualTo("SO-0009");
        assertThat(reused.sequenceValue()).isNull();
        assertThat(recycleService.list(null, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst()
                .getStatus()).isEqualTo(CodeRecycleStatus.USED);
        assertThat(generate("orderNo").value()).isEqualTo("SO-0001");
    }

    @Test
    void shouldRejectActiveLedgerRebindingToDifferentSourceRecord() {
        CodeRule rule = rule("orderNo", true);
        ruleService.saveRuleTree(rule);
        ledgerService.upsertActiveBinding(rule, "SO-0001", CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET, "order-1");

        assertThatThrownBy(() -> ledgerService.upsertActiveBinding(rule, "SO-0001",
                CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET, "order-2"))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("already occupied");

        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo("order-1");
    }

    private GenerateCodeResult generate(String fieldName) {
        return generateService.generate(new GenerateCodeCommand(
                "crm.order",
                "main",
                null,
                fieldName,
                null,
                LocalDateTime.parse("2026-06-08T10:00:00"),
                Map.of(),
                null
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
                segment(CodeSegmentType.CONSTANT, fieldName.equals("assetNo") ? "AS-" : "SO-", null),
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
