package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.platform.metadata.ConfigurationReferenceDeletionGuard;
import net.ximatai.muyun.spring.platform.metadata.PlatformMetadataSchemaEnsureService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeRuleServiceContractTest {
    private final CodeRuleSegmentService segmentService = new CodeRuleSegmentService(new TestMemoryDao<>());
    private final CodeSequencePolicyService sequencePolicyService = new CodeSequencePolicyService(new TestMemoryDao<>());
    private final CodeValueMappingService mappingService = new CodeValueMappingService(new TestMemoryDao<>());
    private final CodeRuleService ruleService = new CodeRuleService(
            new TestMemoryDao<>(),
            segmentService,
            sequencePolicyService,
            mappingService
    );
    private final CodePreviewService previewService = new CodePreviewService(
            new FormulaEngine(Clock.fixed(Instant.parse("2026-06-08T01:02:03Z"), ZoneOffset.UTC)),
            Clock.fixed(Instant.parse("2026-06-08T01:02:03Z"), ZoneOffset.UTC)
    );

    @Test
    void shouldSaveAndViewRuleTreeWithChildren() {
        CodeRule rule = baseRule("orderNo", CodeFieldRole.PRIMARY);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                mappingSegment("orderType", mapping("repair", "RP")),
                sequenceSegment()
        ));
        rule.setSequencePolicy(sequencePolicy(100L, 4));

        CodeRule saved = ruleService.saveRuleTree(rule);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getSegments()).hasSize(3);
        assertThat(saved.getSegments().get(1).getMappings()).singleElement()
                .extracting(CodeValueMapping::getTargetValue)
                .isEqualTo("RP");
        assertThat(saved.getSequencePolicy().getStartValue()).isEqualTo(100L);
    }

    @Test
    void shouldRejectMoreThanOnePrimaryCodeFieldForSameBusinessObject() {
        CodeRule primary = baseRule("orderNo", CodeFieldRole.PRIMARY);
        primary.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "SO", null)));
        ruleService.saveRuleTree(primary);

        CodeRule anotherPrimary = baseRule("externalNo", CodeFieldRole.PRIMARY);
        anotherPrimary.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "EX", null)));

        assertThatThrownBy(() -> ruleService.saveRuleTree(anotherPrimary))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only have one PRIMARY");
    }

    @Test
    void shouldResolveEnabledEffectiveGlobalRuleByTargetField() {
        CodeRule expired = baseRule("orderNo", CodeFieldRole.NORMAL);
        expired.setEffectiveTo(LocalDateTime.parse("2026-01-01T00:00:00"));
        expired.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "OLD", null)));
        ruleService.saveRuleTree(expired);

        CodeRule active = baseRule("orderNo", CodeFieldRole.NORMAL);
        active.setEffectiveFrom(LocalDateTime.parse("2026-06-01T00:00:00"));
        active.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "NEW", null)));
        ruleService.saveRuleTree(active);

        ResolvedCodeRule resolved = ruleService.resolveRule(new ResolveCodeRuleCommand(
                "crm.order",
                "main",
                null,
                "orderNo",
                null,
                LocalDateTime.parse("2026-06-08T10:00:00")
        ));

        assertThat(resolved).isNotNull();
        assertThat(resolved.rule().getId()).isEqualTo(active.getId());
        assertThat(resolved.resolvedOrganizationId()).isNull();
    }

    @Test
    void shouldPreferOrganizationScopedRuleThenFallbackToGlobal() {
        CodeRule orgRule = baseRule("orderNo", CodeFieldRole.PRIMARY);
        orgRule.setOrgScopeType(CodeOrgScopeType.ORG);
        orgRule.setOrgScopeId("org-root");
        orgRule.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "O", null)));

        CodeRuleService scopedService = new CodeRuleService(
                new TestMemoryDao<>(),
                new CodeRuleSegmentService(new TestMemoryDao<>()),
                new CodeSequencePolicyService(new TestMemoryDao<>()),
                new CodeValueMappingService(new TestMemoryDao<>()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of((OrganizationHierarchyService) organizationId -> List.of(organizationId, "org-root"))
        );
        CodeRule global = scopedService.saveRuleTree(globalCopy(CodeFieldRole.PRIMARY));
        scopedService.saveRuleTree(orgRule);

        ResolvedCodeRule resolved = scopedService.resolveRule(new ResolveCodeRuleCommand(
                "crm.order",
                "main",
                null,
                "orderNo",
                "org-child",
                LocalDateTime.parse("2026-06-08T10:00:00")
        ));

        assertThat(resolved.resolvedOrganizationId()).isEqualTo("org-root");
        assertThat(resolved.rule().getOrgScopeType()).isEqualTo(CodeOrgScopeType.ORG);

        ResolvedCodeRule fallback = scopedService.resolveRule(new ResolveCodeRuleCommand(
                "crm.order",
                "main",
                null,
                "orderNo",
                null,
                LocalDateTime.parse("2026-06-08T10:00:00")
        ));
        assertThat(fallback.resolvedOrganizationId()).isNull();
        assertThat(fallback.rule().getId()).isEqualTo(global.getId());
    }

    @Test
    void shouldRejectMultiplePrimaryRulesForSameTargetFieldAndSameScope() {
        CodeRule primary = baseRule("orderNo", CodeFieldRole.PRIMARY);
        primary.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "SO", null)));
        ruleService.saveRuleTree(primary);

        CodeRule duplicate = baseRule("orderNo", CodeFieldRole.PRIMARY);
        duplicate.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "NO", null)));

        assertThatThrownBy(() -> ruleService.saveRuleTree(duplicate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same scope");
    }

    @Test
    void shouldRejectInvalidAutomaticRuleTreeBeforeRuntimeGeneration() {
        CodeRule withoutSegments = baseRule("orderNo", CodeFieldRole.PRIMARY);
        assertThatThrownBy(() -> ruleService.saveRuleTree(withoutSegments))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires at least one segment");

        CodeRule sequenceWithoutPolicy = baseRule("externalNo", CodeFieldRole.NORMAL);
        sequenceWithoutPolicy.setSegments(List.of(sequenceSegment()));
        assertThatThrownBy(() -> ruleService.saveRuleTree(sequenceWithoutPolicy))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires sequencePolicy");

        CodeRule policyWithoutSequence = baseRule("assetNo", CodeFieldRole.NORMAL);
        policyWithoutSequence.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "AS", null)));
        policyWithoutSequence.setSequencePolicy(sequencePolicy(1L, 4));
        assertThatThrownBy(() -> ruleService.saveRuleTree(policyWithoutSequence))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires a SEQUENCE segment");
    }

    @Test
    void shouldRejectInvalidSegmentSpecificConfiguration() {
        CodeRule missingSource = baseRule("orderNo", CodeFieldRole.PRIMARY);
        missingSource.setSegments(List.of(segment(CodeSegmentType.FIELD_VALUE, null, null)));
        assertThatThrownBy(() -> ruleService.saveRuleTree(missingSource))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires sourceRef");

        CodeRule missingMappings = baseRule("externalNo", CodeFieldRole.NORMAL);
        missingMappings.setSegments(List.of(sourceSegment(CodeSegmentType.VALUE_MAPPING, "orderType")));
        assertThatThrownBy(() -> ruleService.saveRuleTree(missingMappings))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires mappings");

        CodeRule duplicateDefault = baseRule("assetNo", CodeFieldRole.NORMAL);
        duplicateDefault.setSegments(List.of(mappingSegment("orderType", defaultMapping("A"), defaultMapping("B"))));
        assertThatThrownBy(() -> ruleService.saveRuleTree(duplicateDefault))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("one default mapping");
    }

    @Test
    void shouldRejectManualRuleWithAutomaticSequenceConfiguration() {
        CodeRule manual = baseRule("manualNo", CodeFieldRole.NORMAL);
        manual.setMode(CodeMode.MANUAL);
        manual.setSegments(List.of(sequenceSegment()));
        manual.setSequencePolicy(sequencePolicy(1L, 4));

        assertThatThrownBy(() -> ruleService.saveRuleTree(manual))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("MANUAL code rule cannot declare automatic sequence configuration");
    }

    @Test
    void shouldRejectLinkedUpdateWithoutSourceDependency() {
        CodeRule rule = baseRule("orderNo", CodeFieldRole.PRIMARY);
        rule.setLinkedUpdate(Boolean.TRUE);
        rule.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "SO", null), sequenceSegment()));
        rule.setSequencePolicy(sequencePolicy(1L, 4));

        assertThatThrownBy(() -> ruleService.saveRuleTree(rule))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("linkedUpdate code rule requires");
    }

    @Test
    void shouldValidateTargetAgainstExistingModuleEntityAndFieldWhenServicesAreAvailable() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>(), event -> {});
        MetadataService metadataService = new MetadataService(
                new TestMemoryDao<>(),
                TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                Optional.empty(),
                TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                TestBeanProviders.empty(ModuleMetadataRelationService.class),
                event -> {});
        FieldSpecService fieldTypeService = new FieldSpecService(new TestMemoryDao<>(), Mockito.mock(BaseDao.class));
        MetadataFieldService fieldService = new MetadataFieldService(
                new TestMemoryDao<>(),
                metadataService,
                fieldTypeService,
                TestBeanProviders.empty(PlatformDynamicRuntimeRefreshCoordinator.class),
                TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                TestBeanProviders.empty(ModuleMetadataRelationService.class),
                TestBeanProviders.empty(PlatformModuleService.class));
        ModuleMetadataRelationService relationService = new ModuleMetadataRelationService(
                new TestMemoryDao<>(),
                moduleService,
                metadataService,
                Optional.empty(),
                TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                TestBeanProviders.empty(MetadataFieldService.class),
                event -> {});
        ModuleMetadataFieldService moduleFieldService = new ModuleMetadataFieldService(
                new TestMemoryDao<>(), relationService, metadataService, fieldService);

        PlatformModule module = new PlatformModule();
        module.setApplicationAlias("crm");
        module.setAlias("crm.order");
        moduleService.insert(module);
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias("crm");
        metadata.setAlias("main");
        metadata.setSchemaName("public");
        metadata.setTableName("crm_order");
        metadataService.insert(metadata);
        FieldSpec stringType = new FieldSpec();
        stringType.setAlias("string");
        stringType.setFieldType(FieldType.STRING);
        fieldTypeService.insert(stringType);
        MetadataField orderNo = new MetadataField();
        orderNo.setMetadataId(metadata.getId());
        orderNo.setFieldName("orderNo");
        orderNo.setColumnName("order_no");
        orderNo.setFieldSpecAlias("string");
        fieldService.insert(orderNo);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias("crm.order");
        relation.setMetadataId(metadata.getId());
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias("main");
        String relationId = relationService.insert(relation);
        ModuleMetadataField moduleField = moduleFieldService.ensureForRelation(relationId).getFirst();

        CodeRuleService validatingService = new CodeRuleService(
                new TestMemoryDao<>(),
                new CodeRuleSegmentService(new TestMemoryDao<>()),
                new CodeSequencePolicyService(new TestMemoryDao<>()),
                new CodeValueMappingService(new TestMemoryDao<>()),
                Optional.of(moduleService),
                Optional.of(relationService),
                Optional.of(moduleFieldService),
                Optional.of(metadataService),
                Optional.of(fieldService),
                Optional.empty()
        );
        CodeRule rule = baseRule("legacyOrderNo", CodeFieldRole.PRIMARY);
        rule.setFieldName(null);
        rule.setModuleMetadataFieldId(moduleField.getId());
        rule.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "SO", null)));

        CodeRule saved = validatingService.saveRuleTree(rule);

        assertThat(saved.getModuleMetadataFieldId()).isEqualTo(moduleField.getId());
        assertThat(saved.getMetadataFieldId()).isEqualTo(orderNo.getId());
        assertThat(saved.getFieldName()).isEqualTo("orderNo");
        ResolvedCodeRule resolvedByModuleField = validatingService.resolveRule(new ResolveCodeRuleCommand(
                "crm.order",
                "main",
                moduleField.getId(),
                null,
                "legacyOrderNo",
                null,
                null
        ));
        assertThat(resolvedByModuleField.rule().getId()).isEqualTo(saved.getId());

        CodeRule missingField = baseRule("missingField", CodeFieldRole.NORMAL);
        missingField.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "X", null)));
        assertThatThrownBy(() -> validatingService.saveRuleTree(missingField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("existing target field");
    }

    @Test
    void shouldPreviewDraftWithSupportedSegmentsWithoutAdvancingSequence() {
        CodeRule rule = baseRule("orderNo", CodeFieldRole.PRIMARY);
        rule.setSequencePolicy(sequencePolicy(10L, 4));
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                dateSegment(),
                sourceSegment(CodeSegmentType.FIELD_VALUE, "customer"),
                sourceSegment(CodeSegmentType.CONTEXT_VAR, "dept"),
                mappingSegment("orderType", mapping("install", "IN")),
                formulaSegment("CONCAT('-', UPPER({suffix}))"),
                sequenceSegment()
        ));
        Map<String, Object> context = Map.of(
                "customer", "AC",
                "dept", "D1",
                "orderType", "install",
                "suffix", "x"
        );

        CodePreviewResult first = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule, context, null, LocalDateTime.parse("2026-06-08T10:11:12"), null));
        CodePreviewResult second = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule, context, null, LocalDateTime.parse("2026-06-08T10:11:12"), null));
        CodePreviewResult requested = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule, context, null, LocalDateTime.parse("2026-06-08T10:11:12"), 7L));

        assertThat(first.value()).isEqualTo("SO-202606ACD1IN-X0010");
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(requested.value()).isEqualTo("SO-202606ACD1IN-X0007");
        assertThat(first.segments()).hasSize(7);
        assertThat(first.segments()).last()
                .extracting(CodePreviewSegmentResult::segmentType, CodePreviewSegmentResult::value)
                .containsExactly(CodeSegmentType.SEQUENCE, "0010");
    }

    @Test
    void shouldUseDefaultValueMappingWithoutSourceValueWhenNoSourceMatches() {
        CodeValueMapping defaultMapping = new CodeValueMapping();
        defaultMapping.setDefaultMapping(Boolean.TRUE);
        defaultMapping.setTargetValue("DF");
        defaultMapping.setEnabled(Boolean.TRUE);
        CodeRule rule = baseRule("orderNo", CodeFieldRole.PRIMARY);
        rule.setSegments(List.of(mappingSegment("orderType", defaultMapping)));

        CodeRule saved = ruleService.saveRuleTree(rule);
        CodePreviewResult preview = previewService.previewDraft(new PreviewCodeRuleCommand(
                saved,
                Map.of("orderType", "unknown"),
                null,
                LocalDateTime.parse("2026-06-08T10:11:12"),
                null
        ));

        assertThat(saved.getSegments()).singleElement()
                .extracting(segment -> segment.getMappings().getFirst().getSourceValue())
                .isNull();
        assertThat(preview.value()).isEqualTo("DF");
    }

    private CodeRule baseRule(String fieldName, CodeFieldRole role) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias("crm.order");
        rule.setEntityAlias("main");
        rule.setFieldName(fieldName);
        rule.setFieldRole(role);
        rule.setEnabled(Boolean.TRUE);
        return rule;
    }

    private CodeRule globalCopy(CodeFieldRole role) {
        CodeRule rule = baseRule("orderNo", role);
        rule.setSegments(List.of(segment(CodeSegmentType.CONSTANT, "G", null)));
        return rule;
    }

    private CodeRuleSegment segment(CodeSegmentType type, String fixedValue, String sourceRef) {
        CodeRuleSegment segment = new CodeRuleSegment();
        segment.setSegmentType(type);
        segment.setFixedValue(fixedValue);
        segment.setSourceRef(sourceRef);
        return segment;
    }

    private CodeRuleSegment sourceSegment(CodeSegmentType type, String sourceRef) {
        return segment(type, null, sourceRef);
    }

    private CodeRuleSegment dateSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SYSTEM_TIME, null, null);
        segment.setDateFormat(CodeDateFormat.YYYYMM);
        return segment;
    }

    private CodeRuleSegment formulaSegment(String expression) {
        CodeRuleSegment segment = segment(CodeSegmentType.FORMULA, null, null);
        segment.setFormulaExpr(expression);
        return segment;
    }

    private CodeRuleSegment sequenceSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SEQUENCE, null, null);
        segment.setSequenceBasis(Boolean.TRUE);
        return segment;
    }

    private CodeRuleSegment mappingSegment(String sourceRef, CodeValueMapping mapping) {
        CodeRuleSegment segment = sourceSegment(CodeSegmentType.VALUE_MAPPING, sourceRef);
        segment.setMappings(List.of(mapping));
        return segment;
    }

    private CodeRuleSegment mappingSegment(String sourceRef, CodeValueMapping... mappings) {
        CodeRuleSegment segment = sourceSegment(CodeSegmentType.VALUE_MAPPING, sourceRef);
        segment.setMappings(List.of(mappings));
        return segment;
    }

    private CodeValueMapping mapping(String sourceValue, String targetValue) {
        CodeValueMapping mapping = new CodeValueMapping();
        mapping.setSourceValue(sourceValue);
        mapping.setTargetValue(targetValue);
        mapping.setEnabled(Boolean.TRUE);
        return mapping;
    }

    private CodeValueMapping defaultMapping(String targetValue) {
        CodeValueMapping mapping = new CodeValueMapping();
        mapping.setDefaultMapping(Boolean.TRUE);
        mapping.setTargetValue(targetValue);
        mapping.setEnabled(Boolean.TRUE);
        return mapping;
    }

    private CodeSequencePolicy sequencePolicy(long startValue, int length) {
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(startValue);
        policy.setSequenceLength(length);
        return policy;
    }
}
