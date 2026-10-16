package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;
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

class DynamicCodeLedgerRuntimeSupportTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldFallbackDefaultBucketOnlyForOldContextRenderFailure() {
        CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>());
        CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
        DynamicCodeLedgerRuntimeSupport support = new DynamicCodeLedgerRuntimeSupport(
                new CodePreviewService(new FormulaEngine(clock), clock),
                ledgerService,
                recycleService,
                clock
        );
        CodeRule rule = rule();
        rule.setAllowRecycle(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null, false),
                segment(CodeSegmentType.FIELD_VALUE, null, "deletedCategory", true),
                sequenceSegment()
        ));

        support.releaseCode(rule, "SO-A0001", null, Map.of(), "record-1",
                CodeLedgerAction.RELEASED_BY_DELETE, LocalDateTime.parse("2026-06-08T10:00:00"));

        CodeLedgerEntry ledger = ledgerService.findByRuleAndValue(rule.getId(), "SO-A0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(ledger.getBasisKey()).isEqualTo(CodeSequenceState.DEFAULT_BUCKET);
        CodeRecycleEntry recycle = recycleService.selectByRuleId(rule.getId(), 10).getFirst();
        assertThat(recycle.getBasisKey()).isEqualTo(CodeSequenceState.DEFAULT_BUCKET);
        assertThat(recycle.getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
    }

    @Test
    void shouldNotFallbackDefaultBucketForRuleStructureFailure() {
        CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>());
        CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
        DynamicCodeLedgerRuntimeSupport support = new DynamicCodeLedgerRuntimeSupport(
                new CodePreviewService(new FormulaEngine(clock), clock),
                ledgerService,
                recycleService,
                clock
        );
        CodeRule rule = rule();
        rule.setSequencePolicy(null);
        rule.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "SO-", null, false), sequenceSegment()));

        assertThatThrownBy(() -> support.releaseCode(rule, "SO-0001", null, Map.of(), "record-1",
                CodeLedgerAction.RELEASED_BY_DELETE, LocalDateTime.parse("2026-06-08T10:00:00")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sequencePolicy");

        assertThat(ledgerService.findByRuleAndValue(rule.getId(), "SO-0001")).isNull();
        assertThat(recycleService.selectByRuleId(rule.getId(), 10)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            CodePreviewService.SOURCE_VALUE_MISSING, CodePreviewService.VALUE_MAPPING_MISSING})
    void releaseFallbackUsesMachineCodeDespiteTranslatedMessage(String code) {
        var preview = org.mockito.Mockito.mock(CodePreviewService.class);
        org.mockito.Mockito.when(preview.previewDraft(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PlatformException(code, 400, "来源值已不存在"));
        var ledger = new CodeLedgerEntryService(new TestMemoryDao<>());
        var recycle = new CodeRecycleEntryService(new TestMemoryDao<>());
        var support = new DynamicCodeLedgerRuntimeSupport(preview, ledger, recycle, clock);
        var rule = rule();
        support.releaseCode(rule, "SO-0001", null, Map.of(), "record-1",
                CodeLedgerAction.RELEASED_BY_DELETE, LocalDateTime.now(clock));
        assertThat(ledger.findByRuleAndValue(rule.getId(), "SO-0001").getBasisKey())
                .isEqualTo(CodeSequenceState.DEFAULT_BUCKET);
    }

    @Test
    void releaseDoesNotFallbackForMisleadingDiagnosticText() {
        var preview = org.mockito.Mockito.mock(CodePreviewService.class);
        org.mockito.Mockito.when(preview.previewDraft(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PlatformException("Code segment source has no value: misleading"));
        var ledger = new CodeLedgerEntryService(new TestMemoryDao<>());
        var support = new DynamicCodeLedgerRuntimeSupport(preview, ledger,
                new CodeRecycleEntryService(new TestMemoryDao<>()), clock);
        assertThatThrownBy(() -> support.releaseCode(rule(), "SO-0001", null, Map.of(), "record-1",
                CodeLedgerAction.RELEASED_BY_DELETE, LocalDateTime.now(clock)))
                .isInstanceOf(PlatformException.class);
        assertThat(ledger.findByRuleAndValue("rule-1", "SO-0001")).isNull();
    }

    private CodeRule rule() {
        CodeRule rule = new CodeRule();
        rule.setId("rule-1");
        rule.setModuleAlias("crm.order");
        rule.setEntityAlias("main");
        rule.setFieldName("orderNo");
        rule.setMode(CodeMode.AUTO);
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        return rule;
    }

    private CodeRuleSegment sequenceSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SEQUENCE, null, null, false);
        segment.setLength(4);
        return segment;
    }

    private CodeRuleSegment segment(CodeSegmentType type, String fixedValue, String sourceRef, boolean sequenceBasis) {
        CodeRuleSegment segment = new CodeRuleSegment();
        segment.setSegmentType(type);
        segment.setFixedValue(fixedValue);
        segment.setSourceRef(sourceRef);
        segment.setSequenceBasis(sequenceBasis);
        return segment;
    }
}
