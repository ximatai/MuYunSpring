package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleActionContributionRegistrar;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordGenerationRuleServiceTest {
    private final RecordGenerationObjectMappingService objectMappingService = new RecordGenerationObjectMappingService(new TestMemoryDao<>());
    private final RecordGenerationFieldMappingService fieldMappingService = new RecordGenerationFieldMappingService(new TestMemoryDao<>());
    private final RecordGenerationSplitPolicyService splitPolicyService = new RecordGenerationSplitPolicyService(new TestMemoryDao<>());
    private final RecordGenerationSplitGroupFieldService splitGroupFieldService = new RecordGenerationSplitGroupFieldService(new TestMemoryDao<>());

    @Test
    void shouldSaveAndViewGenerationRuleTree() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule rule = baseRule();
        RecordGenerationObjectMapping mapping = objectMapping(
                fieldMapping("contractNo", "sourceNo"),
                constantMapping("status", "draft"),
                formulaMapping("amountWithTax", "amount * 1.06"));
        mapping.setSplitDriver(Boolean.TRUE);
        mapping.setSplitPolicy(groupSplit("productId"));
        rule.setObjectMappings(List.of(mapping));

        RecordGenerationRule saved = ruleService.saveRuleTree(rule);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getActionCode()).isEqualTo("generateInvoice");
        assertThat(saved.getObjectMappings()).singleElement()
                .satisfies(objectMapping -> {
                    assertThat(objectMapping.getTargetObjectAlias()).isEqualTo("main");
                    assertThat(objectMapping.getFieldMappings()).extracting(RecordGenerationFieldMapping::getTargetField)
                            .containsExactly("contractNo", "status", "amountWithTax");
                    assertThat(objectMapping.getFieldMappings()).extracting(RecordGenerationFieldMapping::getMappingType)
                            .containsExactly(RecordGenerationFieldSourceType.DIRECT, RecordGenerationFieldSourceType.CONSTANT, RecordGenerationFieldSourceType.FORMULA);
                    assertThat(objectMapping.getFieldMappings().getFirst().getDefaultValue()).isEqualTo("N/A");
                    assertThat(objectMapping.getSplitPolicy().getGroupFields()).singleElement()
                            .extracting(RecordGenerationSplitGroupField::getFieldName)
                            .isEqualTo("productId");
                });
    }

    @Test
    void shouldSyncActionWhenEnabledAndDisableWhenRuleDisabled() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModuleActionService actionService =
                new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        moduleService.insert(module("sales.contract"));
        GenerationModuleActionContributor contributor =
                new GenerationModuleActionContributor(new ModuleActionContributionRegistrar(actionService));
        RecordGenerationRuleService ruleService = ruleService(Optional.of(contributor));

        RecordGenerationRule saved = ruleService.saveRuleTree(baseRule());
        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "generateInvoice");
        assertThat(action.getEnabled()).isTrue();

        saved.setEnabled(Boolean.FALSE);
        ruleService.saveRuleTree(saved);

        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "generateInvoice").getEnabled())
                .isFalse();
    }

    @Test
    void shouldRejectBlankSourceOrTargetModuleAlias() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule blankSource = baseRule();
        blankSource.setSourceModuleAlias(" ");
        assertThatThrownBy(() -> ruleService.saveRuleTree(blankSource))
                .isInstanceOf(RuntimeException.class);

        RecordGenerationRule blankTarget = baseRule();
        blankTarget.setTargetModuleAlias(null);
        assertThatThrownBy(() -> ruleService.saveRuleTree(blankTarget))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRejectRecordGenerationObjectMappingWithoutRuleId() {
        RecordGenerationObjectMapping objectMapping = new RecordGenerationObjectMapping();
        objectMapping.setTargetObjectAlias("main");

        assertThatThrownBy(() -> objectMappingService.insert(objectMapping))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleId");
    }

    @Test
    void shouldRejectDuplicateTargetFieldInOneRecordGenerationObjectMapping() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule rule = baseRule();
        rule.setObjectMappings(List.of(objectMapping(
                fieldMapping("contractNo", "sourceNo"),
                fieldMapping("contractNo", "externalNo"))));

        assertThatThrownBy(() -> ruleService.saveRuleTree(rule))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("duplicate targetField");
    }

    @Test
    void shouldRejectDuplicateActionCodeWithinSourceModule() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        ruleService.saveRuleTree(baseRule());
        RecordGenerationRule duplicate = baseRule();

        assertThatThrownBy(() -> ruleService.saveRuleTree(duplicate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("actionCode must be unique");
    }

    @Test
    void shouldValidateReferenceGenerateRuleDirection() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule saved = ruleService.saveRuleTree(baseRule());
        RecordGenerationReferenceGenerateRuleValidator validator =
                new RecordGenerationReferenceGenerateRuleValidator(ruleService.getDao());

        validator.validateReferenceGenerateRule(saved.getId(), "sales.contract", "finance.invoice");

        assertThatThrownBy(() -> validator.validateReferenceGenerateRule(saved.getId(),
                "finance.invoice", "sales.contract"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("generation rule direction");
    }

    @Test
    void shouldRejectMultipleSplitDriverObjectMappings() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule rule = baseRule();
        RecordGenerationObjectMapping first = objectMapping(fieldMapping("contractNo", "sourceNo"));
        first.setSplitDriver(Boolean.TRUE);
        first.setSplitPolicy(groupSplit("productId"));
        RecordGenerationObjectMapping second = objectMapping(fieldMapping("lineNo", "sourceLineNo"));
        second.setSplitDriver(Boolean.TRUE);
        second.setSplitPolicy(groupSplit("warehouseId"));
        rule.setObjectMappings(List.of(first, second));

        assertThatThrownBy(() -> ruleService.saveRuleTree(rule))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("one split driver object mapping");
    }

    @Test
    void shouldAllowGroupAndQuantitySplitOnSingleDriverObjectMapping() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule rule = baseRule();
        RecordGenerationObjectMapping mapping = objectMapping(fieldMapping("contractNo", "sourceNo"));
        RecordGenerationSplitPolicy splitPolicy = groupSplit("productId");
        splitPolicy.setQuantityField("quantity");
        splitPolicy.setQuantityStep(1);
        mapping.setSplitDriver(Boolean.TRUE);
        mapping.setSplitPolicy(splitPolicy);
        rule.setObjectMappings(List.of(mapping));

        RecordGenerationRule saved = ruleService.saveRuleTree(rule);

        assertThat(saved.getObjectMappings()).singleElement()
                .satisfies(savedMapping -> {
                    assertThat(savedMapping.getSplitDriver()).isTrue();
                    assertThat(savedMapping.getSplitPolicy().getQuantityField()).isEqualTo("quantity");
                    assertThat(savedMapping.getSplitPolicy().getGroupFields()).singleElement()
                            .extracting(RecordGenerationSplitGroupField::getFieldName)
                            .isEqualTo("productId");
                });
    }

    @Test
    void shouldResolveModuleMetadataFieldIdsInGenerationMappings() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        MetadataService metadataService = new MetadataService(new TestMemoryDao<>());
        FieldSpecService fieldTypeService = new FieldSpecService(new TestMemoryDao<>());
        MetadataFieldService fieldService = new MetadataFieldService(new TestMemoryDao<>(), metadataService, fieldTypeService);
        ModuleMetadataRelationService relationService = new ModuleMetadataRelationService(
                new TestMemoryDao<>(), moduleService, metadataService);
        ModuleMetadataFieldService moduleFieldService = new ModuleMetadataFieldService(
                new TestMemoryDao<>(), relationService, metadataService, fieldService);
        FieldSpec stringType = new FieldSpec();
        stringType.setAlias("string");
        stringType.setFieldType(FieldType.STRING);
        fieldTypeService.insert(stringType);
        moduleService.insert(module("sales.contract"));
        moduleService.insert(module("finance.invoice"));
        List<ModuleMetadataField> sourceFields = moduleFields(moduleFieldService, relationService, metadataService,
                fieldService, "sales.contract", "main", "source_no", "quantity", "product_id");
        List<ModuleMetadataField> targetFields = moduleFields(moduleFieldService, relationService, metadataService,
                fieldService, "finance.invoice", "main", "contract_no");
        ModuleMetadataField sourceNo = moduleField(sourceFields, "sourceNo");
        ModuleMetadataField quantity = moduleField(sourceFields, "quantity");
        ModuleMetadataField productId = moduleField(sourceFields, "productId");
        ModuleMetadataField contractNo = moduleField(targetFields, "contractNo");
        RecordGenerationRuleService ruleService = new RecordGenerationRuleService(
                new TestMemoryDao<>(),
                objectMappingService,
                fieldMappingService,
                splitPolicyService,
                splitGroupFieldService,
                Optional.of(moduleFieldService),
                Optional.empty());
        RecordGenerationFieldMapping fieldMapping = fieldMapping(null, null);
        fieldMapping.setSourceModuleMetadataFieldId(sourceNo.getId());
        fieldMapping.setTargetModuleMetadataFieldId(contractNo.getId());
        RecordGenerationSplitPolicy splitPolicy = groupSplit(null);
        splitPolicy.setQuantityModuleMetadataFieldId(quantity.getId());
        splitPolicy.setQuantityStep(1);
        splitPolicy.getGroupFields().getFirst().setModuleMetadataFieldId(productId.getId());
        RecordGenerationObjectMapping mapping = objectMapping(fieldMapping);
        mapping.setSplitDriver(Boolean.TRUE);
        mapping.setSplitPolicy(splitPolicy);
        RecordGenerationRule rule = baseRule();
        rule.setObjectMappings(List.of(mapping));

        RecordGenerationRule saved = ruleService.saveRuleTree(rule);

        RecordGenerationObjectMapping savedMapping = saved.getObjectMappings().getFirst();
        RecordGenerationFieldMapping savedField = savedMapping.getFieldMappings().getFirst();
        assertThat(savedField.getSourceModuleMetadataFieldId()).isEqualTo(sourceNo.getId());
        assertThat(savedField.getSourceField()).isEqualTo("sourceNo");
        assertThat(savedField.getTargetModuleMetadataFieldId()).isEqualTo(contractNo.getId());
        assertThat(savedField.getTargetField()).isEqualTo("contractNo");
        assertThat(savedMapping.getSplitPolicy().getQuantityModuleMetadataFieldId()).isEqualTo(quantity.getId());
        assertThat(savedMapping.getSplitPolicy().getQuantityField()).isEqualTo("quantity");
        assertThat(savedMapping.getSplitPolicy().getGroupFields().getFirst().getModuleMetadataFieldId())
                .isEqualTo(productId.getId());
        assertThat(savedMapping.getSplitPolicy().getGroupFields().getFirst().getFieldName()).isEqualTo("productId");
    }

    @Test
    void shouldRejectSplitPolicyOnNonDriverObjectMapping() {
        RecordGenerationRuleService ruleService = ruleServiceWithoutContributor();
        RecordGenerationRule rule = baseRule();
        RecordGenerationObjectMapping mapping = objectMapping(fieldMapping("contractNo", "sourceNo"));
        mapping.setSplitPolicy(groupSplit("productId"));
        rule.setObjectMappings(List.of(mapping));

        assertThatThrownBy(() -> ruleService.saveRuleTree(rule))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires splitDriver");
    }

    private RecordGenerationRuleService ruleServiceWithoutContributor() {
        return ruleService(Optional.empty());
    }

    private RecordGenerationRuleService ruleService(Optional<GenerationModuleActionContributor> contributor) {
        return new RecordGenerationRuleService(
                new TestMemoryDao<>(),
                objectMappingService,
                fieldMappingService,
                splitPolicyService,
                splitGroupFieldService,
                contributor);
    }

    private RecordGenerationRule baseRule() {
        RecordGenerationRule rule = new RecordGenerationRule();
        rule.setSourceModuleAlias("sales.contract");
        rule.setTargetModuleAlias("finance.invoice");
        rule.setActionCode("generateInvoice");
        rule.setTitle("生成发票");
        rule.setEnabled(Boolean.TRUE);
        return rule;
    }

    private RecordGenerationObjectMapping objectMapping(RecordGenerationFieldMapping... fieldMappings) {
        RecordGenerationObjectMapping mapping = new RecordGenerationObjectMapping();
        mapping.setSourceObjectAlias("main");
        mapping.setTargetObjectAlias("main");
        mapping.setFieldMappings(List.of(fieldMappings));
        return mapping;
    }

    private RecordGenerationFieldMapping fieldMapping(String targetField, String sourceField) {
        RecordGenerationFieldMapping mapping = new RecordGenerationFieldMapping();
        mapping.setTargetField(targetField);
        mapping.setSourceField(sourceField);
        mapping.setMappingType(RecordGenerationFieldSourceType.DIRECT);
        mapping.setDefaultValue("N/A");
        return mapping;
    }

    private RecordGenerationFieldMapping constantMapping(String targetField, String constantValue) {
        RecordGenerationFieldMapping mapping = new RecordGenerationFieldMapping();
        mapping.setTargetField(targetField);
        mapping.setMappingType(RecordGenerationFieldSourceType.CONSTANT);
        mapping.setConstantValue(constantValue);
        return mapping;
    }

    private RecordGenerationFieldMapping formulaMapping(String targetField, String formulaExpr) {
        RecordGenerationFieldMapping mapping = new RecordGenerationFieldMapping();
        mapping.setTargetField(targetField);
        mapping.setMappingType(RecordGenerationFieldSourceType.FORMULA);
        mapping.setFormulaExpr(formulaExpr);
        return mapping;
    }

    private RecordGenerationSplitPolicy groupSplit(String fieldName) {
        RecordGenerationSplitPolicy policy = new RecordGenerationSplitPolicy();
        RecordGenerationSplitGroupField groupField = new RecordGenerationSplitGroupField();
        groupField.setFieldName(fieldName);
        policy.setGroupFields(List.of(groupField));
        return policy;
    }

    private List<ModuleMetadataField> moduleFields(ModuleMetadataFieldService moduleFieldService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService,
                                                   String moduleAlias,
                                                   String relationAlias,
                                                   String... columnNames) {
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        metadata.setAlias(relationAlias);
        metadata.setTableName(moduleAlias.replace('.', '_') + "_" + relationAlias);
        metadataService.insert(metadata);
        for (String columnName : columnNames) {
            MetadataField field = new MetadataField();
            field.setMetadataId(metadata.getId());
            field.setFieldName(toFieldName(columnName));
            field.setColumnName(columnName);
            field.setTitle(toFieldName(columnName));
            field.setFieldSpecAlias("string");
            fieldService.insert(field);
        }
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadata.getId());
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias(relationAlias);
        String relationId = relationService.insert(relation);
        return moduleFieldService.ensureForRelation(relationId);
    }

    private ModuleMetadataField moduleField(List<ModuleMetadataField> fields, String fieldName) {
        return fields.stream()
                .filter(field -> moduleFieldServiceFieldName(field).equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private String moduleFieldServiceFieldName(ModuleMetadataField moduleField) {
        if (moduleField.getMetadataFieldId() == null) {
            return "";
        }
        return moduleField.getTitle();
    }

    private String toFieldName(String columnName) {
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (char ch : columnName.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
            } else {
                builder.append(upperNext ? Character.toUpperCase(ch) : ch);
                upperNext = false;
            }
        }
        return builder.toString();
    }

    private PlatformModule module(String alias) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setAlias(alias);
        module.setTitle(alias);
        return module;
    }
}
