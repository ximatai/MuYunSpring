package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryKind;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldRole;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffectService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilterService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleActionBindingType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.workflow.DynamicWorkflowActionExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class PlatformModuleDefinitionCompilerTest {
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final TestMemoryDao<Metadata> metadataDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataField> fieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<FieldSpec> fieldTypeDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataFieldConfig> fieldConfigDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataFieldReferenceConfig> referenceConfigDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataView> viewDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataViewField> viewFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformModuleAction> actionDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataFormulaRule> formulaRuleDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataField> moduleFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataFieldFilter> moduleFieldFilterDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataFieldAffect> moduleFieldAffectDao = new TestMemoryDao<>();
    private final TestMemoryDao<DictionaryCategory> categoryDao = new TestMemoryDao<>();
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MetadataService metadataService = new MetadataService(metadataDao);
    private final DictionaryCategoryService categoryService = new DictionaryCategoryService(categoryDao);
    private final FieldSpecService fieldTypeService = new FieldSpecService(fieldTypeDao);
    private final MetadataFieldService fieldService = new MetadataFieldService(fieldDao, metadataService, fieldTypeService);
    private final ModuleMetadataRelationService relationService =
            new ModuleMetadataRelationService(relationDao, moduleService, metadataService);
    private final MetadataFieldConfigService fieldConfigService =
            new MetadataFieldConfigService(fieldConfigDao, fieldService, metadataService, fieldTypeService,
                    categoryService, relationService);
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler =
            new MetadataFieldDefinitionCompiler(fieldTypeService, fieldConfigService, null, fieldService);
    private final MetadataFieldReferenceConfigService referenceConfigService =
            new MetadataFieldReferenceConfigService(referenceConfigDao, fieldService, metadataService,
                    fieldTypeService, moduleService, relationService);
    private final MetadataViewService viewService = new MetadataViewService(viewDao, relationService);
    private final MetadataViewFieldService viewFieldService =
            new MetadataViewFieldService(viewFieldDao, viewService, fieldService, relationService);
    private final PlatformModuleActionService actionService =
            new PlatformModuleActionService(actionDao, moduleService);
    private final ModuleMetadataFormulaRuleService formulaRuleService =
            new ModuleMetadataFormulaRuleService(formulaRuleDao, relationService, fieldService);
    private final ModuleMetadataFieldService moduleFieldService =
            new ModuleMetadataFieldService(moduleFieldDao, relationService, metadataService, fieldService,
                    fieldTypeService,
                    Optional.of((ruleId, referenceModuleAlias, ownerModuleAlias) -> {
                    }));
    private final ModuleMetadataFieldFilterService moduleFieldFilterService =
            new ModuleMetadataFieldFilterService(moduleFieldFilterDao, moduleFieldService);
    private final ModuleMetadataFieldAffectService moduleFieldAffectService =
            new ModuleMetadataFieldAffectService(moduleFieldAffectDao, moduleFieldService);
    private final PlatformModuleDefinitionCompiler compiler =
            new PlatformModuleDefinitionCompiler(moduleService, metadataService, fieldService, fieldDefinitionCompiler,
                    referenceConfigService, relationService, viewService, viewFieldService, actionService,
                    formulaRuleService, moduleFieldService, moduleFieldFilterService, moduleFieldAffectService,
                    new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator());

    {
        fieldTypeService.insert(fieldType("string", FieldType.STRING, 128));
        fieldTypeService.insert(fieldType("id", FieldType.STRING, 32));
        fieldTypeService.insert(fieldType("integer", FieldType.INTEGER, null));
        fieldTypeService.insert(fieldType("decimal", FieldType.DECIMAL, null));
        fieldTypeService.insert(fieldType("boolean", FieldType.BOOLEAN, null));
        fieldTypeService.insert(fieldType("date", FieldType.DATE, null));
    }

    @Test
    void shouldCompileMainMetadataIntoDynamicModuleDefinition() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY));
        fieldService.insert(titleField(metadataId));
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(status);
        MetadataFieldConfig statusConfig = fieldConfig(status.getId());
        statusConfig.setDictionaryCategoryAlias("customer_status");
        fieldConfigService.insert(statusConfig);
        fieldService.insert(sortField(metadataId));
        fieldService.insert(enabledField(metadataId));
        fieldService.insert(parentField(metadataId));
        relationService.insert(mainRelation("crm.customer", metadataId));

        ModuleDefinition definition = compiler.compile("crm.customer");

        assertThat(definition.moduleAlias()).isEqualTo("crm.customer");
        assertThat(definition.entities()).hasSize(1);
        assertThat(definition.entities().getFirst().alias()).isEqualTo("customer");
        assertThat(definition.entities().getFirst().schemaName()).isEqualTo(MetadataService.DEFAULT_SCHEMA);
        assertThat(definition.entities().getFirst().tableName()).isEqualTo("crm_customer");
        assertThat(definition.entities().getFirst().capabilities())
                .contains(EntityCapability.TREE, EntityCapability.SORT, EntityCapability.REFERENCE, EntityCapability.ENABLE);
        assertThat(definition.entities().getFirst().fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("title", "status", "sortOrder", "enabled", "parentId");
        assertThat(definition.entities().getFirst().fields().get(1).dictionaryBinding().categoryAlias())
                .isEqualTo("customer_status");
    }

    @Test
    void shouldCompileModuleMetadataFieldOverridesIntoDynamicFieldDefinition() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY));
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(status);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        ModuleMetadataField moduleField = moduleFieldService.ensureForRelation(relationId).getFirst();
        moduleField.setDefaultValue("draft");
        moduleField.setValidationRegex("[a-z]+");
        moduleField.setCloneable(Boolean.FALSE);
        moduleField.setDictionaryApplicationAlias("crm");
        moduleField.setDictionaryCategoryAlias("customer_status");
        moduleFieldService.update(moduleField);

        ModuleDefinition definition = compiler.compile("crm.customer");

        FieldDefinition compiled = field(definition, "status");
        assertThat(compiled.behavior().defaultValue()).isEqualTo("draft");
        assertThat(compiled.behavior().validationRegex()).isEqualTo("[a-z]+");
        assertThat(compiled.behavior().copyable()).isFalse();
        assertThat(compiled.dictionaryBinding().categoryAlias()).isEqualTo("customer_status");
    }

    @Test
    void shouldCompileMeasureUnitMetadataIntoDynamicFieldDefinition() {
        moduleService.insert(module("sales.order", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        MetadataField quantityUnit = field(metadataId, "quantityUnit", "quantity_unit", FieldType.STRING);
        quantityUnit.setFieldForm(MetadataFieldForm.COMPANION);
        quantityUnit.setFieldRole(MetadataFieldRole.MEASURE_UNIT);
        quantityUnit.setOwnerFieldId(quantity.getId());
        fieldService.insert(quantityUnit);
        MetadataField quantityBase = field(metadataId, "quantityBase", "quantity_base", FieldType.DECIMAL);
        quantityBase.setFieldForm(MetadataFieldForm.SHADOW);
        quantityBase.setFieldRole(MetadataFieldRole.MEASURE_BASE_VALUE);
        quantityBase.setOwnerFieldId(quantity.getId());
        fieldService.insert(quantityBase);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setDefaultUnitCode("box");
        moduleField.setUnitFieldId(quantityUnit.getId());
        moduleField.setBaseValueFieldId(quantityBase.getId());
        moduleField.setUnitRequired(true);
        moduleFieldService.update(moduleField);

        ModuleDefinition definition = compiler.compile("sales.order");

        FieldDefinition compiled = field(definition, "quantity");
        assertThat(compiled.measureUnit().enabled()).isTrue();
        assertThat(compiled.measureUnit().categoryAlias()).isEqualTo("package");
        assertThat(compiled.measureUnit().mode()).isEqualTo(FieldMeasureUnitMode.SELECTABLE);
        assertThat(compiled.measureUnit().defaultUnitCode()).isEqualTo("box");
        assertThat(compiled.measureUnit().unitFieldName()).isEqualTo("quantityUnit");
        assertThat(compiled.measureUnit().baseValueFieldName()).isEqualTo("quantityBase");
        assertThat(compiled.measureUnit().baseUnitCategoryAlias()).isEqualTo("package");
        assertThat(compiled.measureUnit().baseUnitCode()).isEqualTo("bottle");
        assertThat(compiled.measureUnit().unitRequired()).isTrue();
    }

    @Test
    void shouldCompileMoneyMetadataIntoDynamicFieldDefinition() {
        moduleService.insert(module("sales.order", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        MetadataField amountCurrency = field(metadataId, "amountCurrency", "amount_currency", FieldType.STRING);
        amountCurrency.setFieldForm(MetadataFieldForm.COMPANION);
        amountCurrency.setFieldRole(MetadataFieldRole.MONEY_CURRENCY);
        amountCurrency.setOwnerFieldId(amount.getId());
        fieldService.insert(amountCurrency);
        MetadataField amountBase = field(metadataId, "amountBase", "amount_base", FieldType.DECIMAL);
        amountBase.setFieldForm(MetadataFieldForm.SHADOW);
        amountBase.setFieldRole(MetadataFieldRole.MONEY_BASE_AMOUNT);
        amountBase.setOwnerFieldId(amount.getId());
        fieldService.insert(amountBase);
        MetadataField orderDate = field(metadataId, "orderDate", "order_date", FieldType.DATE);
        fieldService.insert(orderDate);
        MetadataField amountExchangeRate = field(metadataId, "amountExchangeRate", "amount_exchange_rate", FieldType.DECIMAL);
        amountExchangeRate.setFieldForm(MetadataFieldForm.SHADOW);
        amountExchangeRate.setFieldRole(MetadataFieldRole.MONEY_EXCHANGE_RATE);
        amountExchangeRate.setOwnerFieldId(amount.getId());
        fieldService.insert(amountExchangeRate);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyDefaultCurrencyCode("USD");
        moduleField.setMoneyCurrencyFieldId(amountCurrency.getId());
        moduleField.setMoneyBaseAmountFieldId(amountBase.getId());
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");
        moduleField.setMoneyRateDateFieldId(orderDate.getId());
        moduleField.setMoneyExchangeRateFieldId(amountExchangeRate.getId());
        moduleField.setMoneyCurrencyRequired(true);
        moduleFieldService.update(moduleField);

        ModuleDefinition definition = compiler.compile("sales.order");

        FieldDefinition compiled = field(definition, "amount");
        assertThat(compiled.money().enabled()).isTrue();
        assertThat(compiled.money().currencyMode()).isEqualTo(FieldMoneyMode.SELECTABLE);
        assertThat(compiled.money().defaultCurrencyCode()).isEqualTo("USD");
        assertThat(compiled.money().currencyFieldName()).isEqualTo("amountCurrency");
        assertThat(compiled.money().baseAmountFieldName()).isEqualTo("amountBase");
        assertThat(compiled.money().baseCurrencyCode()).isEqualTo("CNY");
        assertThat(compiled.money().rateTypeCode()).isEqualTo("SPOT");
        assertThat(compiled.money().rateDateFieldName()).isEqualTo("orderDate");
        assertThat(compiled.money().exchangeRateFieldName()).isEqualTo("amountExchangeRate");
        assertThat(compiled.money().currencyRequired()).isTrue();
    }

    @Test
    void shouldCompileModuleMetadataFieldReferenceIntoDynamicReferenceDefinition() {
        moduleService.insert(module("crm.order", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String orderMetadataId = metadataService.insert(metadata("crm", "order"));
        MetadataField customerId = field(orderMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        MetadataField customerRegion = field(orderMetadataId, "customerRegion", "customer_region", FieldType.STRING);
        fieldService.insert(customerRegion);
        String orderRelationId = relationService.insert(mainRelation("crm.order", orderMetadataId));

        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField id = field(customerMetadataId, "id", "id", FieldType.STRING);
        fieldService.insert(id);
        MetadataField title = titleField(customerMetadataId);
        fieldService.insert(title);
        MetadataField region = field(customerMetadataId, "region", "region", FieldType.STRING);
        fieldService.insert(region);
        String customerRelationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));

        List<ModuleMetadataField> orderFields = moduleFieldService.ensureForRelation(orderRelationId);
        ModuleMetadataField customerField = moduleField(orderFields, customerId.getId());
        ModuleMetadataField formRegion = moduleField(orderFields, customerRegion.getId());
        customerField.setReferenceModuleAlias("crm.customer");
        customerField.setReferenceTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.RESTRICT);
        customerField.setReferenceModuleKeyField("id");
        customerField.setReferenceModuleLabelField("title");
        customerField.setReferenceGenerateRuleId("generate-order");
        customerField.setReferenceQueryTemplateId("customer-query");
        customerField.setReferenceModulePlusFields(java.util.Set.of("region"));
        moduleFieldService.update(customerField);
        ModuleMetadataField referenceRegion = moduleField(
                moduleFieldService.ensureForRelation(customerRelationId),
                region.getId());
        ModuleMetadataFieldFilter filter = new ModuleMetadataFieldFilter();
        filter.setModuleMetadataFieldId(customerField.getId());
        filter.setFormFieldId(formRegion.getId());
        filter.setReferenceFieldId(referenceRegion.getId());
        filter.setOperator(DynamicQueryOperator.EQ);
        moduleFieldFilterService.insert(filter);
        ModuleMetadataFieldAffect affect = new ModuleMetadataFieldAffect();
        affect.setModuleMetadataFieldId(customerField.getId());
        affect.setReferenceFieldId(referenceRegion.getId());
        affect.setTargetFieldId(formRegion.getId());
        moduleFieldAffectService.insert(affect);

        ModuleDefinition definition = compiler.compile("crm.order");

        EntityReferenceDefinition reference = definition.references().getFirst();
        assertThat(reference.sourceField()).isEqualTo("customerId");
        assertThat(reference.targetQualifiedName()).isEqualTo("crm.customer.customer");
        assertThat(reference.integrity().onTargetUnavailable()).isEqualTo(ReferenceTargetUnavailablePolicy.RESTRICT);
        assertThat(reference.keyField()).isEqualTo("id");
        assertThat(reference.labelField()).isEqualTo("title");
        assertThat(reference.generateRuleId()).isEqualTo("generate-order");
        assertThat(reference.queryTemplateId()).isEqualTo("customer-query");
        assertThat(reference.plusFields()).containsExactlyInAnyOrder("region");
        assertThat(reference.filters()).singleElement()
                .satisfies(compiledFilter -> {
                    assertThat(compiledFilter.formField()).isEqualTo("customerRegion");
                    assertThat(compiledFilter.referenceField()).isEqualTo("region");
                    assertThat(compiledFilter.operator()).isEqualTo(DynamicQueryOperator.EQ);
                });
        assertThat(reference.affects()).singleElement()
                .satisfies(compiledAffect -> {
                    assertThat(compiledAffect.referenceField()).isEqualTo("region");
                    assertThat(compiledAffect.targetField()).isEqualTo("customerRegion");
                });
        var descriptorReference = DynamicModuleDescriptor.from(definition).references().getFirst();
        assertThat(descriptorReference.generateRuleId()).isEqualTo("generate-order");
        assertThat(descriptorReference.filters()).singleElement()
                .extracting(filterDescriptor -> filterDescriptor.formField(), filterDescriptor -> filterDescriptor.referenceField())
                .containsExactly("customerRegion", "region");
        assertThat(descriptorReference.affects()).singleElement()
                .extracting(affectDescriptor -> affectDescriptor.referenceField(), affectDescriptor -> affectDescriptor.targetField())
                .containsExactly("region", "customerRegion");
    }

    @Test
    void shouldCompileChildMetadataRelations() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(titleField(lineId));
        fieldService.insert(field(lineId, "invoiceId", "invoice_id", FieldType.STRING));
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        ModuleMetadataRelation child = childRelation("sales.invoice", lineId, invoiceId);
        child.setAutoPopulate(true);
        relationService.insert(child);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.entities()).extracting(entity -> entity.alias())
                .containsExactly("invoice", "invoice_line");
        assertThat(definition.entities().getFirst().capabilities()).contains(EntityCapability.CHILD_RELATION);
        assertThat(definition.relations()).hasSize(1);
        EntityRelationDefinition relation = definition.relations().getFirst();
        assertThat(relation.code()).isEqualTo("lines");
        assertThat(relation.parentEntityAlias()).isEqualTo("invoice");
        assertThat(relation.childEntityAlias()).isEqualTo("invoice_line");
        assertThat(relation.childForeignKeyField()).isEqualTo("invoiceId");
        assertThat(relation.autoPopulate()).isTrue();
        assertThat(definition.references()).singleElement().satisfies(reference -> {
            assertThat(reference.sourceEntityAlias()).isEqualTo("invoice_line");
            assertThat(reference.sourceField()).isEqualTo("invoiceId");
            assertThat(reference.targetQualifiedName()).isEqualTo("sales.invoice.invoice");
        });
        assertThat(definition.associationViews()).hasSize(2);
        EntityAssociationViewDefinition associationView = definition.associationViews().getFirst();
        assertThat(associationView.sourceEntityAlias()).isEqualTo("invoice");
        assertThat(associationView.targetModuleAlias()).isEqualTo("sales.invoice");
        assertThat(associationView.targetEntityAlias()).isEqualTo("invoice_line");
        assertThat(associationView.displayMode()).isEqualTo(AssociationViewDisplayMode.INLINE_LIST);
        assertThat(associationView.relationCode()).isEqualTo("lines");
        assertThat(associationView.queryable()).isTrue();
    }

    @Test
    void shouldRejectReservedModuleCapabilitiesOnChildMetadata() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(titleField(lineId));
        MetadataField sortOrder = field(lineId, "sortOrder", "sort_order", FieldType.INTEGER);
        sortOrder.setSortableField(true);
        fieldService.insert(sortOrder);
        fieldService.insert(field(lineId, "invoiceId", "invoice_id", FieldType.STRING));
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));

        assertThatThrownBy(() -> compiler.compile("sales.invoice"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reserved module capability field: sortOrder");
    }

    @Test
    void shouldRejectEnableCapabilityColumnAliasOnChildMetadata() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(titleField(lineId));
        fieldService.insert(field(lineId, "active", "enabled", FieldType.BOOLEAN));
        fieldService.insert(field(lineId, "invoiceId", "invoice_id", FieldType.STRING));
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));

        assertThatThrownBy(() -> compiler.compile("sales.invoice"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reserved module capability field: active");
    }

    @Test
    void shouldCompileFieldReferenceConfigIntoModuleDefinition() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(field(invoiceId, "code", "code", FieldType.STRING));
        fieldService.insert(titleField(lineId));
        MetadataField invoiceField = field(lineId, "invoiceId", "invoice_id", FieldType.STRING);
        fieldService.insert(invoiceField);
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));
        MetadataFieldReferenceConfig referenceConfig = referenceConfig(invoiceField.getId(), invoiceId);
        referenceConfig.setTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.RESTRICT);
        referenceConfig.setProjectionMappings("title:invoiceTitle,code:invoiceCode");
        referenceConfigService.insert(referenceConfig);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.references()).hasSize(1);
        EntityReferenceDefinition reference = definition.references().getFirst();
        assertThat(reference.sourceEntityAlias()).isEqualTo("invoice_line");
        assertThat(reference.sourceField()).isEqualTo("invoiceId");
        assertThat(reference.targetQualifiedName()).isEqualTo("sales.invoice.invoice");
        assertThat(reference.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("invoiceTitle"));
        assertThat(reference.integrity().onTargetUnavailable()).isEqualTo(ReferenceTargetUnavailablePolicy.RESTRICT);
        assertThat(reference.projections()).hasSize(2);
        assertThat(reference.projections()).anySatisfy(projection -> {
            assertThat(projection.targetField()).isEqualTo("code");
            assertThat(projection.outputField()).isEqualTo("invoiceCode");
        });
        assertThat(DynamicModuleDescriptor.from(definition).references().getFirst().targetEntityAlias()).isEqualTo("invoice");
        assertThat(DynamicModuleDescriptor.from(definition).entities().get(1).fields().stream()
                .filter(field -> field.fieldName().equals("invoiceId"))
                .findFirst())
                .get()
                .extracting(field -> field.reference().targetEntityAlias())
                .isEqualTo("invoice");
        assertThat(definition.associationViews()).extracting(EntityAssociationViewDefinition::code)
                .contains("lines", "invoiceId");
        EntityAssociationViewDefinition referenceView = definition.associationViews().stream()
                .filter(view -> "invoiceId".equals(view.code()))
                .findFirst()
                .orElseThrow();
        assertThat(referenceView.displayMode()).isEqualTo(AssociationViewDisplayMode.LINKED_RECORD);
        assertThat(referenceView.referenceField()).isEqualTo("invoiceId");
        assertThat(referenceView.targetEntityAlias()).isEqualTo("invoice");
        assertThat(referenceView.queryable()).isTrue();
    }

    @Test
    void shouldRejectManyReferenceOnChildForeignKey() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(titleField(lineId));
        MetadataField invoiceField = field(lineId, "invoiceId", "invoice_id", FieldType.STRING);
        fieldService.insert(invoiceField);
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));
        MetadataFieldReferenceConfig referenceConfig = referenceConfig(invoiceField.getId(), invoiceId);
        referenceConfig.setCardinality(ReferenceCardinality.MANY);
        assertThatThrownBy(() -> referenceConfigService.insert(referenceConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must use ONE cardinality");
    }

    @Test
    void shouldRejectChildForeignKeyReferenceThatDoesNotTargetItsParent() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String customerId = metadataService.insert(metadata("sales", "customer"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(titleField(customerId));
        fieldService.insert(titleField(lineId));
        MetadataField invoiceField = field(lineId, "invoiceId", "invoice_id", FieldType.STRING);
        fieldService.insert(invoiceField);
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));
        assertThatThrownBy(() -> referenceConfigService.insert(referenceConfig(invoiceField.getId(), customerId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must reference its parent metadata");
    }

    @Test
    void shouldCompileRelationScopedReferenceToStaticTargetWithProjection() {
        moduleService.insert(module("education.exam", ModuleKind.DYNAMIC));
        moduleService.insert(module("education.student", ModuleKind.STATIC));
        String examId = metadataService.insert(metadata("education", "exam"));
        fieldService.insert(titleField(examId));
        MetadataField studentId = field(examId, "studentId", "student_id", FieldType.STRING);
        fieldService.insert(studentId);
        String relationId = relationService.insert(mainRelation("education.exam", examId));
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(studentId.getId());
        config.setRelationId(relationId);
        config.setTargetModuleAlias("education.student");
        config.setProjectionMappings("title:studentTitle");
        @SuppressWarnings("unchecked") ReferenceAbility<?> student = mock(ReferenceAbility.class);
        ReferenceTarget target = ReferenceTarget.of("education", "student");
        when(student.getModuleAlias()).thenReturn("education.student");
        doReturn(StandardTitledEntity.class).when(student).modelClass();
        PlatformAbilityRuntime.configureReferenceTargetResolver(reference -> target.equals(reference)
                ? Optional.of(student) : Optional.empty());
        try {
            referenceConfigService.insert(config);

            ModuleDefinition definition = compiler.compile("education.exam");

            assertThat(ReferenceTargets.of(student)).isEqualTo(target);
            assertThat(definition.references()).singleElement().satisfies(reference -> {
                assertThat(reference.target()).isEqualTo(target);
                assertThat(reference.projections()).containsExactly(new net.ximatai.muyun.spring.ability.reference.ReferenceProjection(
                        "title", "studentTitle"));
            });
            assertThat(definition.associationViews()).isEmpty();
        } finally {
            PlatformAbilityRuntime.resetReferenceTargetResolver();
        }
    }

    @Test
    void shouldRejectStaticTargetForChildForeignKey() {
        moduleService.insert(module("education.exam", ModuleKind.DYNAMIC));
        moduleService.insert(module("education.student", ModuleKind.STATIC));
        String examId = metadataService.insert(metadata("education", "exam"));
        String lineId = metadataService.insert(metadata("education", "exam_line"));
        fieldService.insert(titleField(examId));
        MetadataField examIdField = field(lineId, "examId", "exam_id", FieldType.STRING);
        fieldService.insert(examIdField);
        relationService.insert(mainRelation("education.exam", examId));
        String lineRelationId = relationService.insert(childRelation("education.exam", lineId, examId, "examId"));
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(examIdField.getId());
        config.setRelationId(lineRelationId);
        config.setTargetModuleAlias("education.student");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Child relation foreign key cannot reference a static target");
    }

    @Test
    void shouldCompileRelationScopedFieldConfigForSameMetadataInDifferentModules() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        moduleService.insert(module("sales.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        fieldService.insert(titleField(metadataId));
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(status);
        String crmRelationId = relationService.insert(mainRelation("crm.customer", metadataId));
        String salesRelationId = relationService.insert(mainRelation("sales.customer", metadataId));
        MetadataFieldConfig crmConfig = fieldConfig(status.getId());
        crmConfig.setRelationId(crmRelationId);
        crmConfig.setQueryable(true);
        fieldConfigService.insert(crmConfig);
        MetadataFieldConfig salesConfig = fieldConfig(status.getId());
        salesConfig.setRelationId(salesRelationId);
        salesConfig.setQueryable(false);
        fieldConfigService.insert(salesConfig);

        ModuleDefinition crmDefinition = compiler.compile("crm.customer");
        ModuleDefinition salesDefinition = compiler.compile("sales.customer");

        assertThat(field(crmDefinition, "status").queryDefinition().queryable()).isTrue();
        assertThat(field(salesDefinition, "status").queryDefinition().queryable()).isFalse();
    }

    @Test
    void shouldCompileRelationScopedReferenceConfigForSameSourceMetadataInDifferentModules() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        moduleService.insert(module("support.ticket", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String ticketId = metadataService.insert(metadata("support", "ticket"));
        String lineId = metadataService.insert(metadata("sales", "work_line"));
        fieldService.insert(titleField(invoiceId));
        fieldService.insert(field(invoiceId, "code", "code", FieldType.STRING));
        fieldService.insert(titleField(ticketId));
        fieldService.insert(field(ticketId, "code", "code", FieldType.STRING));
        fieldService.insert(titleField(lineId));
        MetadataField ownerField = field(lineId, "ownerId", "owner_id", FieldType.STRING);
        fieldService.insert(ownerField);
        relationService.insert(mainRelation("sales.invoice", invoiceId));
        String salesLineRelationId = relationService.insert(childRelation("sales.invoice", lineId, invoiceId, "ownerId"));
        relationService.insert(mainRelation("support.ticket", ticketId));
        String supportLineRelationId = relationService.insert(childRelation("support.ticket", lineId, ticketId, "ownerId"));
        MetadataFieldReferenceConfig defaultReference = referenceConfig(ownerField.getId(), invoiceId);
        defaultReference.setRelationId(salesLineRelationId);
        defaultReference.setProjectionMappings("title:invoiceTitle,code:invoiceCode");
        referenceConfigService.insert(defaultReference);
        MetadataFieldReferenceConfig supportReference = referenceConfig(ownerField.getId(), ticketId);
        supportReference.setRelationId(supportLineRelationId);
        supportReference.setProjectionMappings("title:ticketTitle,code:ticketCode");
        referenceConfigService.insert(supportReference);

        ModuleDefinition salesDefinition = compiler.compile("sales.invoice");
        ModuleDefinition supportDefinition = compiler.compile("support.ticket");

        EntityReferenceDefinition salesReference = salesDefinition.references().getFirst();
        assertThat(salesReference.targetQualifiedName()).isEqualTo("sales.invoice.invoice");
        assertThat(salesReference.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("invoiceTitle"));
        assertThat(salesReference.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("invoiceCode"));
        EntityReferenceDefinition supportReferenceDefinition = supportDefinition.references().getFirst();
        assertThat(supportReferenceDefinition.targetQualifiedName()).isEqualTo("support.ticket.ticket");
        assertThat(supportReferenceDefinition.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("ticketTitle"));
        assertThat(supportReferenceDefinition.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("ticketCode"));
    }

    @Test
    void shouldCompileConfiguredViewsIntoModuleDefinition() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField title = titleField(metadataId);
        fieldService.insert(title);
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        status.setRequired(true);
        fieldService.insert(status);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        String listViewId = viewService.insert(metadataView(relationId, EntityViewType.LIST, "客户列表"));
        MetadataViewField titleViewField = metadataViewField(listViewId, title.getId());
        titleViewField.setTitle("客户名称");
        viewFieldService.insert(titleViewField);
        MetadataViewField statusViewField = metadataViewField(listViewId, status.getId());
        statusViewField.setControlType(ViewControlType.SELECT);
        statusViewField.setReadOnly(true);
        statusViewField.setRequiredOverride(true);
        viewFieldService.insert(statusViewField);

        ModuleDefinition definition = compiler.compile("crm.customer");

        assertThat(definition.views()).hasSize(1);
        EntityViewDefinition view = definition.views().getFirst();
        assertThat(view.entityAlias()).isEqualTo("customer");
        assertThat(view.viewType()).isEqualTo(EntityViewType.LIST);
        assertThat(view.title()).isEqualTo("客户列表");
        assertThat(view.fields()).extracting(field -> field.fieldName())
                .containsExactly("title", "status");
        assertThat(view.fields().getFirst().title()).isEqualTo("客户名称");
        assertThat(view.fields().get(1).controlType()).isEqualTo(ViewControlType.SELECT);
        assertThat(view.fields().get(1).readOnly()).isTrue();
        assertThat(view.fields().get(1).required()).isTrue();
        List<net.ximatai.muyun.spring.dynamic.descriptor.DynamicViewDescriptor> descriptorViews =
                DynamicModuleDescriptor.from(definition).entities().getFirst().views();
        assertThat(descriptorViews).hasSize(2);
        assertThat(descriptorViews.getFirst().fields().get(1).required()).isTrue();
        assertThat(descriptorViews.get(1).viewType()).isEqualTo(EntityViewType.FORM);
    }

    @Test
    void shouldCompileConfiguredActionsIntoModuleDefinition() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        Metadata metadata = metadata("crm", "customer");
        metadata.setDataScopeEnabled(true);
        String metadataId = metadataService.insert(metadata);
        fieldService.insert(titleField(metadataId));
        relationService.insert(mainRelation("crm.customer", metadataId));
        PlatformModuleAction create = moduleAction("crm.customer", "customer", "create");
        create.setTitle("新建客户");
        create.setAvailableExpression("{title} != ''");
        create.setUnavailableMessage("客户名称不能为空");
        actionService.insert(create);
        PlatformModuleAction delete = moduleAction("crm.customer", "customer", "delete");
        delete.setEnabled(false);
        delete.setTitle("删除客户");
        actionService.insert(delete);
        PlatformModuleAction export = moduleAction("crm.customer", "customer", "exportData");
        export.setTitle("导出");
        export.setCategory(EntityActionCategory.DIALOG);
        export.setActionLevel(EntityActionLevel.LIST);
        export.setAccessMode(EntityActionAccessMode.AUTH_REQUIRED);
        export.setDataAuth(true);
        export.setPermissionActionCode("create");
        export.setExecutorType(EntityActionExecutorType.DIALOG);
        export.setExecutorKey("customerExportDialog");
        actionService.insert(export);

        ModuleDefinition definition = compiler.compile("crm.customer");

        assertThat(definition.entities().getFirst().supports(EntityCapability.DATA_SCOPE)).isTrue();
        assertThat(definition.actions())
                .extracting(action -> action.actionCode())
                .containsExactly("create", "delete", "exportData");
        List<net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor> actions =
                DynamicModuleDescriptor.from(definition).entities().getFirst().actions();
        assertThat(actions.stream().filter(action -> action.code().equals("create")).findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.title()).isEqualTo("新建客户");
                    assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.LIST);
                    assertThat(action.availabilityCondition()).isTrue();
                    assertThat(action.unavailableMessage()).isEqualTo("客户名称不能为空");
                    assertThat(action.enabled()).isTrue();
                });
        assertThat(actions.stream().filter(action -> action.code().equals("delete")).findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.enabled()).isFalse();
                });
        assertThat(actions.stream().filter(action -> action.code().equals("exportData")).findFirst())
                .get()
                .satisfies(action -> {                    assertThat(action.title()).isEqualTo("导出");
                    assertThat(action.category()).isEqualTo(EntityActionCategory.DIALOG);
                    assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.LIST);
                    assertThat(action.accessMode()).isEqualTo(EntityActionAccessMode.AUTH_REQUIRED);
                    assertThat(action.actionAuth()).isTrue();
                    assertThat(action.dataAuth()).isTrue();
                    assertThat(action.authInheritActionCode()).isEqualTo("create");
                    assertThat(action.executorType()).isEqualTo(EntityActionExecutorType.DIALOG);
                    assertThat(action.executorKey()).isEqualTo("customerExportDialog");
                });
    }

    @Test
    void shouldAssembleWorkflowActionsWhenMainEntitySupportsApproval() {
        moduleService.insert(module("sales.contract", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "contract"));
        fieldService.insert(titleField(metadataId));
        fieldService.insert(field(metadataId, PlatformAbilityFields.APPROVAL_INSTANCE_FIELD,
                PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN, FieldType.STRING));
        fieldService.insert(field(metadataId, PlatformAbilityFields.APPROVAL_STATUS_FIELD,
                PlatformAbilityFields.APPROVAL_STATUS_COLUMN, FieldType.STRING));
        relationService.insert(mainRelation("sales.contract", metadataId));

        ModuleDefinition definition = compiler.compile("sales.contract");

        assertThat(definition.entities().getFirst().supports(EntityCapability.APPROVAL)).isTrue();
        assertThat(definition.entities().getFirst().supports(EntityCapability.WORKFLOW)).isTrue();
        assertThat(definition.actions())
                .extracting(action -> action.actionCode())
                .containsExactly("submitApproval");
        assertThat(definition.actions())
                .allSatisfy(action -> {
                    assertThat(action.category()).isEqualTo(EntityActionCategory.WORKFLOW);
                    assertThat(action.level()).isEqualTo(EntityActionLevel.RECORD);
                    assertThat(action.executorType()).isEqualTo(EntityActionExecutorType.SERVICE);
                    assertThat(action.executorKey()).isEqualTo(DynamicWorkflowActionExecutor.EXECUTOR_KEY);
                });
    }

    @Test
    void shouldKeepConfiguredWorkflowActionWhenActionCodeConflictsWithAssembly() {
        moduleService.insert(module("sales.contract", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "contract"));
        fieldService.insert(titleField(metadataId));
        fieldService.insert(field(metadataId, PlatformAbilityFields.APPROVAL_INSTANCE_FIELD,
                PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN, FieldType.STRING));
        relationService.insert(mainRelation("sales.contract", metadataId));
        PlatformModuleAction submitApproval = moduleAction("sales.contract", "contract", "submitApproval");
        submitApproval.setTitle("送审");
        submitApproval.setExecutorType(EntityActionExecutorType.SERVICE);
        submitApproval.setExecutorKey("customWorkflow");
        submitApproval.setCategory(EntityActionCategory.WORKFLOW);
        submitApproval.setActionLevel(EntityActionLevel.RECORD);
        actionService.insert(submitApproval);

        ModuleDefinition definition = compiler.compile("sales.contract");

        assertThat(definition.actions().stream()
                .filter(action -> action.actionCode().equals("submitApproval"))
                .findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.title()).isEqualTo("送审");
                    assertThat(action.executorKey()).isEqualTo("customWorkflow");
                });
        assertThat(definition.actions())
                .extracting(action -> action.actionCode())
                .containsExactly("submitApproval");
    }

    @Test
    void shouldCompileContributedWorkflowActionFromModuleActionCatalog() {
        moduleService.insert(module("sales.contract", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "contract"));
        fieldService.insert(titleField(metadataId));
        relationService.insert(mainRelation("sales.contract", metadataId));
        PlatformModuleAction syncWorkflow = moduleAction("sales.contract", "contract", "syncWorkflow");
        syncWorkflow.setTitle("同步流程");
        syncWorkflow.setCategory(EntityActionCategory.WORKFLOW);
        syncWorkflow.setActionLevel(EntityActionLevel.RECORD);
        syncWorkflow.setExecutorType(EntityActionExecutorType.SERVICE);
        syncWorkflow.setExecutorKey(DynamicWorkflowActionExecutor.EXECUTOR_KEY);
        syncWorkflow.setBindingType(ModuleActionBindingType.WORKFLOW_DEFINITION);
        syncWorkflow.setBindingAlias("sync");
        actionService.insert(syncWorkflow);

        ModuleDefinition definition = compiler.compile("sales.contract");

        assertThat(definition.actions())
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.actionCode()).isEqualTo("syncWorkflow");
                    assertThat(action.title()).isEqualTo("同步流程");
                    assertThat(action.category()).isEqualTo(EntityActionCategory.WORKFLOW);
                    assertThat(action.executorKey()).isEqualTo(DynamicWorkflowActionExecutor.EXECUTOR_KEY);
                });
    }

    @Test
    void shouldRejectDataAuthActionWhenMetadataDataScopeIsDisabled() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        fieldService.insert(titleField(metadataId));
        relationService.insert(mainRelation("crm.customer", metadataId));
        PlatformModuleAction export = moduleAction("crm.customer", "customer", "exportData");
        export.setTitle("导出");
        export.setDataAuth(true);
        export.setExecutorType(EntityActionExecutorType.SERVICE);
        export.setExecutorKey("exportService");
        actionService.insert(export);

        assertThatThrownBy(() -> compiler.compile("crm.customer"))
                .isInstanceOf(net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException.class)
                .hasMessageContaining("data auth action requires DATA_SCOPE capability");
    }

    @Test
    void shouldCompileChildEntityActionWhenModuleActionDeclaresEntityAlias() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String contactMetadataId = metadataService.insert(metadata("crm", "customer_contact"));
        fieldService.insert(titleField(customerMetadataId));
        fieldService.insert(titleField(contactMetadataId));
        fieldService.insert(field(contactMetadataId, "invoiceId", "invoice_id", FieldType.STRING));
        relationService.insert(mainRelation("crm.customer", customerMetadataId));
        relationService.insert(childRelation("crm.customer", contactMetadataId, customerMetadataId));
        PlatformModuleAction childAction = moduleAction("crm.customer", "customer_contact", "auditContact");
        childAction.setTitle("审核联系人");
        actionService.insert(childAction);

        ModuleDefinition definition = compiler.compile("crm.customer");

        assertThat(definition.actions())
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.entityAlias()).isEqualTo("customer_contact");
                    assertThat(action.actionCode()).isEqualTo("auditContact");
                    assertThat(action.title()).isEqualTo("审核联系人");
                });
    }

    @Test
    void shouldCompileRelationScopedFormulaRulesIntoModuleDefinition() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "invoice"));
        fieldService.insert(field(metadataId, "amount", "amount", FieldType.INTEGER));
        String relationId = relationService.insert(mainRelation("sales.invoice", metadataId));
        ModuleMetadataFormulaRule validation = formulaRule(relationId, "amountPositive", "{amount} > 0");
        validation.setRulePhase(FormulaRulePhase.ACTION_BEFORE_EXECUTE);
        validation.setMessageTemplate("金额必须大于 0");
        formulaRuleService.insert(validation);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.entities().getFirst().formulaRules())
                .hasSize(1)
                .first()
                .satisfies(rule -> {
                    assertThat(rule.code()).isEqualTo("amountPositive");
                    assertThat(rule.kind()).isEqualTo(FormulaRuleKind.VALIDATION);
                    assertThat(rule.phase()).isEqualTo(FormulaRulePhase.ACTION_BEFORE_EXECUTE);
                    assertThat(rule.severity()).isEqualTo(FormulaIssueLevel.ERROR);
                    assertThat(rule.messageTemplate()).isEqualTo("金额必须大于 0");
                    assertThat(rule.stopOnError()).isTrue();
                });
    }

    @Test
    void shouldCompileFormulaRuleTargetingVirtualField() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "invoice"));
        fieldService.insert(field(metadataId, "amount", "amount", FieldType.INTEGER));
        MetadataField displayAmount = field(metadataId, "displayAmount", "display_amount", FieldType.STRING);
        displayAmount.setFieldForm(MetadataFieldForm.VIRTUAL);
        fieldService.insert(displayAmount);
        String relationId = relationService.insert(mainRelation("sales.invoice", metadataId));
        ModuleMetadataFormulaRule rule = formulaRule(relationId, "displayAmountCalc",
                "'Amount ' + {amount}");
        rule.setRuleKind(FormulaRuleKind.CALCULATION);
        rule.setTargetField("displayAmount");
        formulaRuleService.insert(rule);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.entities().getFirst().formulaRules())
                .singleElement()
                .satisfies(formula -> {
                    assertThat(formula.code()).isEqualTo("displayAmountCalc");
                    assertThat(formula.targetField()).isEqualTo("displayAmount");
                });
        assertThat(field(definition, "displayAmount").isPhysical()).isFalse();
    }

    @Test
    void shouldRejectFormulaRuleWithUnknownField() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "invoice"));
        fieldService.insert(field(metadataId, "amount", "amount", FieldType.INTEGER));
        String relationId = relationService.insert(mainRelation("sales.invoice", metadataId));

        assertThatThrownBy(() -> formulaRuleService.insert(formulaRule(relationId, "amountPositive", "{amout} > 0")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Metadata formula field does not exist: amout");
    }

    @Test
    void shouldCompileChildFieldAssignmentFormulaRule() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(field(invoiceId, "amount", "amount", FieldType.INTEGER));
        fieldService.insert(field(lineId, "quantity", "quantity", FieldType.INTEGER));
        fieldService.insert(field(lineId, "price", "price", FieldType.INTEGER));
        fieldService.insert(field(lineId, "lineAmount", "line_amount", FieldType.INTEGER));
        fieldService.insert(field(lineId, "invoiceId", "invoice_id", FieldType.STRING));
        String relationId = relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));
        ModuleMetadataFormulaRule rule = formulaRule(relationId, "lineAmountCalc",
                "SUM({lines.lineAmount} = {lines.quantity} * {lines.price})");
        rule.setRuleKind(FormulaRuleKind.CALCULATION);
        formulaRuleService.insert(rule);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.entities().getFirst().formulaRules())
                .singleElement()
                .satisfies(formula -> {
                    assertThat(formula.code()).isEqualTo("lineAmountCalc");
                    assertThat(formula.targetField()).isNull();
                    assertThat(formula.expression()).isEqualTo(
	                            "SUM({lines.lineAmount} = {lines.quantity} * {lines.price})");
	                });
    }

    @Test
    void shouldCompileChildTargetFieldFormulaRule() {
        moduleService.insert(module("sales.invoice", ModuleKind.DYNAMIC));
        String invoiceId = metadataService.insert(metadata("sales", "invoice"));
        String lineId = metadataService.insert(metadata("sales", "invoice_line"));
        fieldService.insert(field(invoiceId, "amount", "amount", FieldType.INTEGER));
        fieldService.insert(field(lineId, "quantity", "quantity", FieldType.INTEGER));
        fieldService.insert(field(lineId, "price", "price", FieldType.INTEGER));
        fieldService.insert(field(lineId, "lineAmount", "line_amount", FieldType.INTEGER));
        fieldService.insert(field(lineId, "invoiceId", "invoice_id", FieldType.STRING));
        String relationId = relationService.insert(mainRelation("sales.invoice", invoiceId));
        relationService.insert(childRelation("sales.invoice", lineId, invoiceId));
        ModuleMetadataFormulaRule rule = formulaRule(relationId, "lineAmountCalc",
                "{lines.quantity} * {lines.price}");
        rule.setRuleKind(FormulaRuleKind.CALCULATION);
        rule.setTargetField("lines.lineAmount");
        formulaRuleService.insert(rule);

        ModuleDefinition definition = compiler.compile("sales.invoice");

        assertThat(definition.entities().getFirst().formulaRules())
                .singleElement()
                .satisfies(formula -> {
                    assertThat(formula.code()).isEqualTo("lineAmountCalc");
                    assertThat(formula.targetField()).isEqualTo("lines.lineAmount");
                    assertThat(formula.expression()).isEqualTo("{lines.quantity} * {lines.price}");
                });
    }

    @Test
    void shouldRejectActionConfigThatDoesNotMatchEntityCapability() {
        moduleService.insert(module("crm.customer", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        fieldService.insert(titleField(metadataId));
        relationService.insert(mainRelation("crm.customer", metadataId));
        actionService.insert(moduleAction("crm.customer", "customer", "sort"));

        assertThatThrownBy(() -> compiler.compile("crm.customer"))
                .isInstanceOf(net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException.class)
                .hasMessageContaining("standard action is not supported");
    }

    @Test
    void shouldRejectStaticModuleAndDynamicModuleWithoutMainMetadata() {
        moduleService.insert(module("crm.report", ModuleKind.STATIC));
        moduleService.insert(module("crm.empty", ModuleKind.DYNAMIC));

        assertThatThrownBy(() -> compiler.compile("crm.report"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("DYNAMIC module");
        assertThatThrownBy(() -> compiler.compile("crm.empty"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("MAIN metadata");
    }

    private PlatformModule module(String alias, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setModuleKind(kind);
        module.setTitle(alias);
        return module;
    }

    private Metadata metadata(String applicationAlias, String alias) {
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(alias);
        metadata.setTitle(alias);
        return metadata;
    }

    private DictionaryCategory category(String applicationAlias, String alias, DictionaryCategoryKind kind) {
        DictionaryCategory category = new DictionaryCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setCategoryKind(kind);
        category.setTitle(alias);
        return category;
    }

    private MetadataField field(String metadataId, String fieldName, String columnName, FieldType fieldType) {
        MetadataField field = new MetadataField();
        field.setMetadataId(metadataId);
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(fieldType.name().toLowerCase());
        field.setTitle(fieldName);
        return field;
    }

    private MetadataField titleField(String metadataId) {
        MetadataField field = field(metadataId, "title", "title", FieldType.STRING);
        field.setTitleField(true);
        return field;
    }

    private MetadataField sortField(String metadataId) {
        MetadataField field = field(metadataId, "sortOrder", "sort_order", FieldType.INTEGER);
        field.setSortableField(true);
        return field;
    }

    private MetadataField enabledField(String metadataId) {
        return field(metadataId, "enabled", "enabled", FieldType.BOOLEAN);
    }

    private MetadataField parentField(String metadataId) {
        MetadataField field = field(metadataId, "parentId", "parent_id", FieldType.STRING);
        field.setFieldSpecAlias("id");
        return field;
    }

    private FieldSpec fieldType(String alias, FieldType fieldType, Integer length) {
        FieldSpec type = new FieldSpec();
        type.setAlias(alias);
        type.setTitle(alias);
        type.setFieldType(fieldType);
        type.setDefaultLength(length);
        return type;
    }

    private MetadataFieldConfig fieldConfig(String fieldId) {
        MetadataFieldConfig config = new MetadataFieldConfig();
        config.setMetadataFieldId(fieldId);
        return config;
    }

    private MetadataFieldReferenceConfig referenceConfig(String fieldId, String targetMetadataId) {
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(fieldId);
        config.setTargetMetadataId(targetMetadataId);
        return config;
    }

    private MetadataView metadataView(String relationId, EntityViewType viewType, String title) {
        MetadataView view = new MetadataView();
        view.setRelationId(relationId);
        view.setViewType(viewType);
        view.setTitle(title);
        return view;
    }

    private MetadataViewField metadataViewField(String viewId, String fieldId) {
        MetadataViewField viewField = new MetadataViewField();
        viewField.setViewId(viewId);
        viewField.setMetadataFieldId(fieldId);
        return viewField;
    }

    private PlatformModuleAction moduleAction(String moduleAlias, String entityAlias, String alias) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(moduleAlias);
        action.setEntityAlias(entityAlias);
        action.setActionCode(alias);
        return action;
    }

    private ModuleMetadataFormulaRule formulaRule(String relationId, String alias, String expression) {
        ModuleMetadataFormulaRule rule = new ModuleMetadataFormulaRule();
        rule.setRelationId(relationId);
        rule.setAlias(alias);
        rule.setExpression(expression);
        return rule;
    }

    private FieldDefinition field(ModuleDefinition definition, String fieldName) {
        return definition.entities().getFirst().fields().stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private ModuleMetadataField moduleField(List<ModuleMetadataField> fields, String metadataFieldId) {
        return fields.stream()
                .filter(field -> field.getMetadataFieldId().equals(metadataFieldId))
                .findFirst()
                .orElseThrow();
    }

    private ModuleMetadataRelation mainRelation(String moduleAlias, String metadataId) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.MAIN);
        relation.setTitle("main");
        return relation;
    }

    private ModuleMetadataRelation childRelation(String moduleAlias, String metadataId, String parentMetadataId) {
        return childRelation(moduleAlias, metadataId, parentMetadataId, "invoiceId");
    }

    private ModuleMetadataRelation childRelation(String moduleAlias,
                                                 String metadataId,
                                                 String parentMetadataId,
                                                 String foreignKey) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setParentMetadataId(parentMetadataId);
        relation.setRelationRole(RelationRole.CHILD);
        relation.setForeignKey(foreignKey);
        relation.setRelationAlias("lines");
        relation.setTitle("lines");
        return relation;
    }

}
