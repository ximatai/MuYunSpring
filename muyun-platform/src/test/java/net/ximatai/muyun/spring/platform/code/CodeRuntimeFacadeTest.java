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

class CodeRuntimeFacadeTest {
    private static final String TARGET_MODULE = "dynamic.sales";
    private static final String TARGET_ENTITY = "order";
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-08T01:02:03Z"), ZoneOffset.UTC);

    @Test
    void shouldGenerateOnlyForTargetBusinessRuleWithoutLedgerBinding() {
        Services services = services();
        CodeRule rule = services.ruleService.saveRuleTree(targetRule());
        CodeRuntimeFacade facade = new CodeRuntimeFacade(
                services.generateService,
                services.ruleService,
                services.ledgerService
        );

        CodeRuntimeResult result = facade.generateOnly(CodeRuntimeCommand.builder(
                        TARGET_MODULE,
                        TARGET_ENTITY,
                        "orderNo")
                .context(Map.of("customerType", "VIP"))
                .sourceRecordId("draft-record")
                .at(LocalDateTime.parse("2026-06-08T10:00:00"))
                .build());

        assertThat(result.value()).isEqualTo("B-VIP0001");
        assertThat(result.ruleId()).isEqualTo(rule.getId());
        assertThat(result.bound()).isFalse();
        assertThat(result.sourceRecordId()).isNull();
        assertThat(services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0001")).isNull();
    }

    @Test
    void shouldIssueAndBindGeneratedCodeToCallerSourceRecord() {
        Services services = services();
        CodeRule rule = services.ruleService.saveRuleTree(targetRule());
        CodeRuntimeFacade facade = new CodeRuntimeFacade(
                services.generateService,
                services.ruleService,
                services.ledgerService
        );

        CodeRuntimeResult result = facade.issueAndBind(CodeRuntimeCommand.builder(
                        TARGET_MODULE,
                        TARGET_ENTITY,
                        "orderNo")
                .context(Map.of("customerType", "VIP"))
                .sourceRecordId("static-a-1")
                .at(LocalDateTime.parse("2026-06-08T10:00:00"))
                .build());

        assertThat(result.value()).isEqualTo("B-VIP0001");
        assertThat(result.bound()).isTrue();
        assertThat(result.sourceRecordId()).isEqualTo("static-a-1");
        CodeLedgerEntry ledger = services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0001");
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo("static-a-1");
        assertThat(ledger.getModuleAlias()).isEqualTo(TARGET_MODULE);
        assertThat(ledger.getEntityAlias()).isEqualTo(TARGET_ENTITY);
    }

    @Test
    void shouldReturnExistingBindingWhenIssueAndBindSameSourceRecordAgain() {
        Services services = services();
        CodeRule rule = services.ruleService.saveRuleTree(targetRule());
        CodeRuntimeFacade facade = new CodeRuntimeFacade(
                services.generateService,
                services.ruleService,
                services.ledgerService
        );
        CodeRuntimeCommand command = CodeRuntimeCommand.builder(
                        TARGET_MODULE,
                        TARGET_ENTITY,
                        "orderNo")
                .context(Map.of("customerType", "VIP"))
                .sourceRecordId("static-a-1")
                .at(LocalDateTime.parse("2026-06-08T10:00:00"))
                .build();

        CodeRuntimeResult first = facade.issueAndBind(command);
        CodeRuntimeResult second = facade.issueAndBind(command);

        assertThat(first.value()).isEqualTo("B-VIP0001");
        assertThat(second.value()).isEqualTo("B-VIP0001");
        assertThat(services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0001").getSourceRecordId())
                .isEqualTo("static-a-1");
        assertThat(services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0002")).isNull();
    }

    @Test
    void shouldSkipActiveLedgerValueWhenIssueAndBind() {
        Services services = services();
        CodeRule rule = services.ruleService.saveRuleTree(targetRule());
        services.ledgerService.upsertActiveBinding(rule, "B-VIP0001", "customerType=VIP",
                CodeSequenceState.DEFAULT_BUCKET, "other-record");
        CodeRuntimeFacade facade = new CodeRuntimeFacade(
                services.generateService,
                services.ruleService,
                services.ledgerService
        );

        CodeRuntimeResult result = facade.issueAndBind(CodeRuntimeCommand.builder(
                        TARGET_MODULE,
                        TARGET_ENTITY,
                        "orderNo")
                .context(Map.of("customerType", "VIP"))
                .sourceRecordId("static-a-1")
                .at(LocalDateTime.parse("2026-06-08T10:00:00"))
                .build());

        assertThat(result.value()).isEqualTo("B-VIP0002");
        assertThat(services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0001").getSourceRecordId())
                .isEqualTo("other-record");
        assertThat(services.ledgerService.findByRuleAndValue(rule.getId(), "B-VIP0002").getSourceRecordId())
                .isEqualTo("static-a-1");
    }

    @Test
    void shouldRequireSourceRecordIdWhenIssueAndBind() {
        Services services = services();
        services.ruleService.saveRuleTree(targetRule());
        CodeRuntimeFacade facade = new CodeRuntimeFacade(
                services.generateService,
                services.ruleService,
                services.ledgerService
        );

        assertThatThrownBy(() -> facade.issueAndBind(CodeRuntimeCommand.builder(
                        TARGET_MODULE,
                        TARGET_ENTITY,
                        "orderNo")
                .context(Map.of("customerType", "VIP"))
                .build()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sourceRecordId");
    }

    private Services services() {
        CodeRuleSegmentService segmentService = new CodeRuleSegmentService(new TestMemoryDao<>());
        CodeSequencePolicyService policyService = new CodeSequencePolicyService(new TestMemoryDao<>());
        CodeValueMappingService mappingService = new CodeValueMappingService(new TestMemoryDao<>());
        CodeRuleService ruleService = new CodeRuleService(
                new TestMemoryDao<>(),
                segmentService,
                policyService,
                mappingService
        );
        CodeSequenceStateService stateService = new CodeSequenceStateService(new TestMemoryDao<>());
        CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
        CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>(), recycleService);
        CodePreviewService previewService = new CodePreviewService(new FormulaEngine(clock), clock);
        CodeGenerateService generateService = new CodeGenerateService(ruleService, previewService, stateService,
                recycleService, null, null, clock);
        return new Services(ruleService, generateService, ledgerService);
    }

    private CodeRule targetRule() {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(TARGET_MODULE);
        rule.setEntityAlias(TARGET_ENTITY);
        rule.setFieldName("orderNo");
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        CodeRuleSegment customerType = segment(CodeSegmentType.FIELD_VALUE, null, "customerType");
        customerType.setSequenceBasis(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "B-", null),
                customerType,
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

    private record Services(CodeRuleService ruleService,
                            CodeGenerateService generateService,
                            CodeLedgerEntryService ledgerService) {
    }
}
