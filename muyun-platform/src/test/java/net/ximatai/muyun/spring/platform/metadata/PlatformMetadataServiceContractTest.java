package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.capability.SortCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewFieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitConversionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldStorageForm;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFieldValueValidator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.currency.Currency;
import net.ximatai.muyun.spring.platform.currency.CurrencyConversionService;
import net.ximatai.muyun.spring.platform.currency.CurrencyService;
import net.ximatai.muyun.spring.platform.currency.ExchangeRate;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateService;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateType;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateTypeService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.currency.MoneyDynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySettingService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryKind;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.measure.MeasureDimension;
import net.ximatai.muyun.spring.platform.measure.MeasureUnit;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitBusinessConversionService;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitCategory;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitCategoryService;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitConversionRuleService;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitConversionService;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitDynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformMetadataServiceContractTest {
    private final MemoryDao<PlatformModule> moduleDao = new MemoryDao<>();
    private final MemoryDao<Metadata> metadataDao = new MemoryDao<>();
    private final MemoryDao<MetadataField> fieldDao = new MemoryDao<>();
    private final MemoryDao<FieldSpec> fieldTypeDao = new MemoryDao<>();
    private final MemoryDao<FieldUiControl> fieldUiTypeDao = new MemoryDao<>();
    private final MemoryDao<FieldUiControlProperty> fieldUiTypeAttributeDao = new MemoryDao<>();
    private final MemoryDao<FieldUiControlBinding> fieldUiTypeFieldMappingDao = new MemoryDao<>();
    private final MemoryDao<MetadataFieldConfig> fieldConfigDao = new MemoryDao<>();
    private final MemoryDao<MetadataFieldProtectionConfig> protectionConfigDao = new MemoryDao<>();
    private final MemoryDao<MetadataFieldReferenceConfig> referenceConfigDao = new MemoryDao<>();
    private final MemoryDao<ModuleMetadataRelation> relationDao = new MemoryDao<>();
    private final MemoryDao<ModuleMetadataField> moduleFieldDao = new MemoryDao<>();
    private final MemoryDao<ModuleMetadataFieldFilter> moduleFieldFilterDao = new MemoryDao<>();
    private final MemoryDao<ModuleMetadataFieldAffect> moduleFieldAffectDao = new MemoryDao<>();
    private final MemoryDao<MetadataView> viewDao = new MemoryDao<>();
    private final MemoryDao<MetadataViewField> viewFieldDao = new MemoryDao<>();
    private final MemoryDao<DictionaryCategory> categoryDao = new MemoryDao<>();
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MetadataService metadataService = new MetadataService(metadataDao);
    private final DictionaryCategoryService categoryService = new DictionaryCategoryService(categoryDao);
    private final FieldSpecService fieldTypeService = new FieldSpecService(fieldTypeDao, fieldUiTypeDao);
    private final FieldUiControlService fieldUiTypeService =
            new FieldUiControlService(fieldUiTypeDao, fieldTypeService);
    private final FieldUiControlPropertyService fieldUiTypeAttributeService =
            new FieldUiControlPropertyService(fieldUiTypeAttributeDao, fieldUiTypeService, fieldTypeService);
    private final FieldUiControlBindingService fieldUiTypeFieldMappingService =
            new FieldUiControlBindingService(fieldUiTypeFieldMappingDao, fieldUiTypeService, fieldTypeService);

    @BeforeEach
    void configureStaticChildAbilities() {
        PlatformAbilityRuntime.configureChildAbilityResolver(request -> {
            if (request.staticModel().equals(FieldUiControlProperty.class)) {
                return Optional.of(fieldUiTypeAttributeService);
            }
            if (request.staticModel().equals(FieldUiControlBinding.class)) {
                return Optional.of(fieldUiTypeFieldMappingService);
            }
            return Optional.empty();
        });
    }

    @AfterEach
    void resetStaticChildAbilities() {
        PlatformAbilityRuntime.resetChildAbilityResolver();
    }
    private final PlatformMetadataSchemaEnsureService schemaEnsureService = mock(PlatformMetadataSchemaEnsureService.class);
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator =
            mock(PlatformDynamicRuntimeRefreshCoordinator.class);
    private final MetadataFieldService fieldService = new MetadataFieldService(fieldDao, metadataService, fieldTypeService,
            Optional.of(runtimeRefreshCoordinator), Optional.of(schemaEnsureService));
    private final ModuleMetadataRelationService relationService =
            new ModuleMetadataRelationService(relationDao, moduleService, metadataService);
    private final ModuleMetadataOrchestrationService orchestrationService =
            new ModuleMetadataOrchestrationService(moduleService, metadataService, relationService);
    private final ModuleMetadataFieldService moduleFieldService =
            new ModuleMetadataFieldService(moduleFieldDao, relationService, metadataService, fieldService,
                    fieldTypeService, Optional.empty(), Optional.of(runtimeRefreshCoordinator));
    private final ModuleMetadataFieldFilterService moduleFieldFilterService =
            new ModuleMetadataFieldFilterService(moduleFieldFilterDao, moduleFieldService);
    private final ModuleMetadataFieldAffectService moduleFieldAffectService =
            new ModuleMetadataFieldAffectService(moduleFieldAffectDao, moduleFieldService);
    private final MetadataFieldProtectionConfigService protectionConfigService =
            new MetadataFieldProtectionConfigService(protectionConfigDao, fieldService, fieldTypeService, fieldConfigDao,
                    Optional.of(runtimeRefreshCoordinator));
    private final MetadataFieldConfigService fieldConfigService =
            new MetadataFieldConfigService(fieldConfigDao, fieldService, metadataService, fieldTypeService,
                    categoryService, relationService, protectionConfigService, Optional.of(runtimeRefreshCoordinator));
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler =
            new MetadataFieldDefinitionCompiler(fieldTypeService, fieldConfigService, protectionConfigService, fieldService);
    private final PlatformMetadataEntityDefinitionCompiler metadataEntityDefinitionCompiler =
            new PlatformMetadataEntityDefinitionCompiler(metadataService, fieldService, fieldDefinitionCompiler);
    private final MetadataFieldReferenceConfigService referenceConfigService =
            new MetadataFieldReferenceConfigService(referenceConfigDao, fieldService, metadataService,
                    fieldTypeService, moduleService, relationService, Optional.of(runtimeRefreshCoordinator));
    private final MetadataViewService viewService = new MetadataViewService(viewDao, relationService);
    private final MetadataViewFieldService viewFieldService =
            new MetadataViewFieldService(viewFieldDao, viewService, fieldService, relationService,
                    fieldUiTypeService, fieldTypeService);

    {
        fieldTypeService.insert(fieldType("string", FieldType.STRING, 128));
        fieldTypeService.insert(fieldType("text", FieldType.TEXT, null));
        fieldTypeService.insert(fieldType("integer", FieldType.INTEGER, null));
        fieldTypeService.insert(fieldType("decimal", FieldType.DECIMAL, null));
        fieldTypeService.insert(fieldType("boolean", FieldType.BOOLEAN, null));
        fieldTypeService.insert(fieldType("date", FieldType.DATE, null));
        fieldTypeService.insert(fieldType("datetime", FieldType.TIMESTAMP, null));
        fieldTypeService.insert(fieldType("zoned_datetime", FieldType.ZONED_TIMESTAMP, null));
        fieldTypeService.insert(fieldType("json", FieldType.JSON, null));
    }

    @Test
    void shouldCreateMetadataWithApplicationScopedAliasAndPhysicalLocation() {
        Metadata metadata = metadata("crm", "customer");

        String id = metadataService.insert(metadata);

        Metadata saved = metadataService.select(id);
        assertThat(saved.getApplicationAlias()).isEqualTo("crm");
        assertThat(saved.getAlias()).isEqualTo("customer");
        assertThat(saved.getSchemaName()).isEqualTo(MetadataService.DEFAULT_SCHEMA);
        assertThat(saved.getTableName()).isEqualTo("crm_customer");
        assertThat(saved.getEnabled()).isTrue();
    }

    @Test
    void shouldProtectSystemManagedMetadataFieldFromOrdinaryMutation() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField managed = field(metadataId, "customerName", "customer_name", FieldType.STRING);
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> fieldService.insert(managed));

        MetadataField protectedUpdate = new MetadataField();
        protectedUpdate.setId(managed.getId());
        protectedUpdate.setVersion(managed.getVersion());
        protectedUpdate.setMetadataId(metadataId);
        protectedUpdate.setFieldName("changedName");
        protectedUpdate.setColumnName("changed_name");
        protectedUpdate.setFieldSpecAlias("string");
        protectedUpdate.setTitle("Changed");

        assertThatThrownBy(() -> fieldService.update(protectedUpdate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThatThrownBy(() -> fieldService.delete(managed.getId()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void shouldAllowOrdinaryEnabledAndSortUpdateOnSystemManagedMetadataField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField managed = field(metadataId, "customerName", "customer_name", FieldType.STRING);
        managed.setSystemManaged(Boolean.TRUE);
        managed.setSortOrder(10);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> fieldService.insert(managed));

        MetadataField update = new MetadataField();
        update.setId(managed.getId());
        update.setVersion(managed.getVersion());
        update.setEnabled(Boolean.FALSE);
        update.setSortOrder(20);

        assertThat(fieldService.update(update)).isEqualTo(1);

        MetadataField selected = fieldService.select(managed.getId());
        assertThat(selected.getEnabled()).isFalse();
        assertThat(selected.getSortOrder()).isEqualTo(20);
        assertThat(selected.getFieldName()).isEqualTo("customerName");
        assertThat(selected.getSystemManaged()).isTrue();
    }

    @Test
    void shouldRefreshDynamicRuntimeWhenMetadataItselfChanges() {
        PlatformDynamicRuntimeRefreshCoordinator coordinator = mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        MetadataService service = new MetadataService(new MemoryDao<>(), Optional.empty(), Optional.of(coordinator));
        Metadata metadata = metadata("crm", "customer");

        String id = service.insert(metadata);

        verify(coordinator).refreshByMetadataId(id);
        clearInvocations(coordinator);

        metadata.setId(id);
        metadata.setTitle("Customer V2");
        service.update(metadata);

        verify(coordinator).refreshByMetadataId(id);
    }

    @Test
    void shouldRejectInvalidMetadataAlias() {
        Metadata metadata = metadata("crm", "Customer");

        assertThatThrownBy(() -> metadataService.insert(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataAlias");
    }

    @Test
    void shouldRejectDuplicateMetadataAliasAndPhysicalTable() {
        metadataService.insert(metadata("crm", "customer"));

        assertThatThrownBy(() -> metadataService.insert(metadata("crm", "customer")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("metadataAlias");

        Metadata duplicateTable = metadata("sales", "customer");
        duplicateTable.setSchemaName(MetadataService.DEFAULT_SCHEMA);
        duplicateTable.setTableName("crm_customer");
        assertThatThrownBy(() -> metadataService.insert(duplicateTable))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("physical table");
    }

    @Test
    void shouldCreateMetadataFieldAndCompileFieldDefinition() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "customerName", "customer_name", FieldType.STRING);
        field.setTitle("客户名称");
        field.setRequired(true);
        field.setTitleField(true);

        fieldService.insert(field);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(definition.fieldName()).isEqualTo("customerName");
        assertThat(definition.columnName()).isEqualTo("customer_name");
        assertThat(definition.type()).isEqualTo(FieldType.STRING);
        assertThat(definition.isRequired()).isTrue();
        assertThat(definition.isTitle()).isTrue();
        assertThat(definition.length()).isEqualTo(128);
    }

    @Test
    void shouldNormalizeMetadataFieldOwnershipAndForm() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "customerName", "customer_name", FieldType.STRING);

        fieldService.insert(field);

        MetadataField saved = fieldService.select(field.getId());
        assertThat(saved.getFieldOwnership()).isEqualTo(MetadataFieldOwnership.BUSINESS);
        assertThat(saved.getFieldForm()).isEqualTo(MetadataFieldForm.PHYSICAL);
        assertThat(saved.getSystemManaged()).isFalse();
    }

    @Test
    void shouldRequireOwnerForCompanionOrShadowField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField owner = field(metadataId, "startAt", "start_at", FieldType.STRING);
        fieldService.insert(owner);

        MetadataField companion = field(metadataId, "startAtTimeZone", "start_at_timezone", FieldType.STRING);
        companion.setFieldForm(MetadataFieldForm.COMPANION);
        companion.setFieldRole(MetadataFieldRole.TIME_ZONE);
        assertThatThrownBy(() -> fieldService.insert(companion))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("owner field");

        companion.setOwnerFieldId(owner.getId());
        fieldService.insert(companion);

        MetadataField saved = fieldService.select(companion.getId());
        assertThat(saved.getFieldForm()).isEqualTo(MetadataFieldForm.COMPANION);
        assertThat(saved.getFieldRole()).isEqualTo(MetadataFieldRole.TIME_ZONE);
        assertThat(saved.getOwnerFieldId()).isEqualTo(owner.getId());
    }

    @Test
    void shouldCompileFieldQueryDefinition() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "customerName", "customer_name", FieldType.STRING);

        fieldService.insert(field);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(definition.queryDefinition().queryable()).isTrue();
        assertThat(definition.queryDefinition().defaultOperator()).isEqualTo(DynamicQueryOperator.LIKE);
    }

    @Test
    void shouldCompileVirtualMetadataFieldAsNonPhysicalAndNotQueryable() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "displayCode", "display_code", FieldType.STRING);
        field.setFieldForm(MetadataFieldForm.VIRTUAL);

        fieldService.insert(field);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(definition.storageForm()).isEqualTo(FieldStorageForm.VIRTUAL);
        assertThat(definition.isPhysical()).isFalse();
        assertThat(definition.queryDefinition().queryable()).isFalse();
        assertThat(fieldDefinitionCompiler.compileQueryDefinition(field.getId(), null).queryable()).isFalse();
    }

    @Test
    void shouldRejectStorageDependentVirtualMetadataFieldBehavior() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField required = field(metadataId, "displayCode", "display_code", FieldType.STRING);
        required.setFieldForm(MetadataFieldForm.VIRTUAL);
        required.setRequired(true);
        assertThatThrownBy(() -> fieldService.insert(required))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual metadata field cannot be required");

        MetadataField queryField = field(metadataId, "displayName", "display_name", FieldType.STRING);
        queryField.setFieldForm(MetadataFieldForm.VIRTUAL);
        fieldService.insert(queryField);
        MetadataFieldConfig queryConfig = fieldConfig(queryField.getId());
        queryConfig.setQueryable(true);
        assertThatThrownBy(() -> fieldConfigService.insert(queryConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual metadata field cannot be queryable");

        MetadataFieldConfig behaviorConfig = fieldConfig(queryField.getId());
        behaviorConfig.setDefaultValue("AUTO");
        assertThatThrownBy(() -> fieldConfigService.insert(behaviorConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual metadata field cannot define default value or validation regex");

        MetadataFieldProtectionConfig protectionConfig = protectionConfig(queryField.getId());
        protectionConfig.setEncryptionMode(FieldEncryptionMode.ENCRYPTED);
        assertThatThrownBy(() -> protectionConfigService.insert(protectionConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual metadata field cannot use storage protection");
    }

    @Test
    void shouldRejectModuleScopedStorageBehaviorOnVirtualMetadataField() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "displayName", "display_name", FieldType.STRING);
        field.setFieldForm(MetadataFieldForm.VIRTUAL);
        fieldService.insert(field);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));

        ModuleMetadataField behavior = moduleField(relationId, field.getId());
        behavior.setDefaultValue("AUTO");
        assertThatThrownBy(() -> moduleFieldService.insert(behavior))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual module metadata field cannot define default value or validation regex");

        ModuleMetadataField measure = moduleField(relationId, field.getId());
        measure.setUnitCategoryAlias("length");
        assertThatThrownBy(() -> moduleFieldService.insert(measure))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual module metadata field cannot define measure unit config");

        ModuleMetadataField money = moduleField(relationId, field.getId());
        money.setMoneyRateTypeCode("SPOT");
        assertThatThrownBy(() -> moduleFieldService.insert(money))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Virtual module metadata field cannot define money config");
    }

    @Test
    void shouldCompileFieldProtectionFromIndependentMetadataConfig() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "mobile", "mobile", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig fieldConfig = fieldConfig(field.getId());
        fieldConfig.setQueryable(false);
        fieldConfigService.insert(fieldConfig);
        MetadataFieldProtectionConfig config = protectionConfig(field.getId());
        config.setEncryptionMode(FieldEncryptionMode.ENCRYPTED);
        config.setSignatureMode(FieldSignatureMode.SIGNED);
        config.setMaskingPolicy(FieldMaskingPolicy.PHONE);

        protectionConfigService.insert(config);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(definition.protection().encryptionMode()).isEqualTo(FieldEncryptionMode.ENCRYPTED);
        assertThat(definition.protection().signatureMode()).isEqualTo(FieldSignatureMode.SIGNED);
        assertThat(definition.protection().maskingPolicy()).isEqualTo(FieldMaskingPolicy.PHONE);
    }

    @Test
    void shouldRefreshDynamicRuntimeWhenFieldConfigChanges() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "customerName", "customer_name", FieldType.STRING);
        fieldService.insert(field);
        clearInvocations(runtimeRefreshCoordinator);

        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setQueryable(false);
        fieldConfigService.insert(config);

        verify(runtimeRefreshCoordinator).refreshByMetadataField(argThat(refreshed ->
                refreshed != null && field.getId().equals(refreshed.getId())));
    }

    @Test
    void shouldRefreshDynamicRuntimeWhenFieldProtectionChanges() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "mobile", "mobile", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig fieldConfig = fieldConfig(field.getId());
        fieldConfig.setQueryable(false);
        fieldConfigService.insert(fieldConfig);
        clearInvocations(runtimeRefreshCoordinator);

        MetadataFieldProtectionConfig config = protectionConfig(field.getId());
        config.setEncryptionMode(FieldEncryptionMode.ENCRYPTED);
        protectionConfigService.insert(config);

        verify(runtimeRefreshCoordinator).refreshByMetadataField(argThat(refreshed ->
                refreshed != null && field.getId().equals(refreshed.getId())));
    }

    @Test
    void shouldRejectInvalidFieldProtectionMetadataConfig() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.INTEGER);
        fieldService.insert(amount);
        MetadataField title = titleField(metadataId);
        fieldService.insert(title);

        MetadataFieldProtectionConfig numericConfig = protectionConfig(amount.getId());
        numericConfig.setEncryptionMode(FieldEncryptionMode.ENCRYPTED);
        assertThatThrownBy(() -> protectionConfigService.insert(numericConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires string field");

        MetadataFieldProtectionConfig titleConfig = protectionConfig(title.getId());
        titleConfig.setSignatureMode(FieldSignatureMode.SIGNED);
        assertThatThrownBy(() -> protectionConfigService.insert(titleConfig))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be unique, indexed, sortable or title field");
    }

    @Test
    void shouldRejectQueryableProtectedStorageFieldRegardlessOfSaveOrder() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField mobile = field(metadataId, "mobile", "mobile", FieldType.STRING);
        fieldService.insert(mobile);

        MetadataFieldProtectionConfig firstProtection = protectionConfig(mobile.getId());
        firstProtection.setEncryptionMode(FieldEncryptionMode.ENCRYPTED);
        assertThatThrownBy(() -> protectionConfigService.insert(firstProtection))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be queryable");

        MetadataFieldConfig disabledQuery = fieldConfig(mobile.getId());
        disabledQuery.setQueryable(false);
        fieldConfigService.insert(disabledQuery);
        protectionConfigService.insert(firstProtection);

        MetadataFieldConfig enabledQuery = fieldConfig(mobile.getId());
        enabledQuery.setId(disabledQuery.getId());
        enabledQuery.setQueryable(true);
        assertThatThrownBy(() -> fieldConfigService.update(enabledQuery))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be queryable");
    }

    @Test
    void shouldNormalizeQueryOperatorSetBeforeSavingFieldType() {
        FieldSpec type = fieldType("custom_string", FieldType.STRING, 128);
        type.setDefaultQueryOperator(null);
        type.setQueryOperators(java.util.Set.of(" LIKE ", "EQ"));

        String id = fieldTypeService.insert(type);

        FieldSpec saved = fieldTypeService.select(id);
        assertThat(saved.getDefaultQueryOperator()).isEqualTo(DynamicQueryOperator.LIKE);
        assertThat(saved.getQueryOperators()).containsExactly("EQ", "LIKE");
        assertThat(saved.queryDefinition().operators()).contains(DynamicQueryOperator.EQ, DynamicQueryOperator.LIKE);
    }

    @Test
    void shouldNormalizeFieldTypeUiAliases() {
        fieldUiTypeService.insert(fieldUiType("text", "输入框", "string", ViewControlType.TEXT));
        fieldUiTypeService.insert(fieldUiType("select", "下拉单选", "string", ViewControlType.SELECT));
        FieldSpec type = fieldType("customer_code", FieldType.STRING, 64);
        type.setDefaultUiControlAlias(" text ");
        type.setUiControlAliases(java.util.Set.of(" text ", "select"));

        String id = fieldTypeService.insert(type);

        FieldSpec saved = fieldTypeService.select(id);
        assertThat(saved.getDefaultUiControlAlias()).isEqualTo("text");
        assertThat(saved.getUiControlAliases()).containsExactlyInAnyOrder("text", "select");
    }

    @Test
    void shouldRejectFieldTypeDefaultUiTypeOutsideAllowedSet() {
        fieldUiTypeService.insert(fieldUiType("text", "输入框", "string", ViewControlType.TEXT));
        fieldUiTypeService.insert(fieldUiType("select", "下拉单选", "string", ViewControlType.SELECT));
        FieldSpec type = fieldType("customer_code", FieldType.STRING, 64);
        type.setDefaultUiControlAlias("text");
        type.setUiControlAliases(java.util.Set.of("select"));

        assertThatThrownBy(() -> fieldTypeService.insert(type))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("default UI type must be included");
    }

    @Test
    void shouldRejectFieldTypeUnknownUiAlias() {
        FieldSpec type = fieldType("customer_code", FieldType.STRING, 64);
        type.setDefaultUiControlAlias("missing_ui");

        assertThatThrownBy(() -> fieldTypeService.insert(type))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires existing UI type");
    }

    @Test
    void shouldUseUiControlAliasAsItsStableRecordId() {
        FieldUiControl uiType = fieldUiType("text_alias", "输入框", "string", ViewControlType.TEXT);
        uiType.setId("custom_ui_type_id");
        fieldUiTypeService.insert(uiType);
        FieldSpec displayString = fieldType("display_string", FieldType.STRING, 128);
        displayString.setDefaultUiControlAlias("text_alias");
        displayString.setUiControlAliases(java.util.Set.of("text_alias"));

        fieldTypeService.insert(displayString);

        assertThat(fieldUiTypeService.select("text_alias").getAlias()).isEqualTo("text_alias");
        assertThat(fieldTypeService.select(displayString.getId()).getDefaultUiControlAlias()).isEqualTo("text_alias");
    }

    @Test
    void shouldDiscardAStalePrimaryValueKeyWhenSwitchingAwayFromCompositeShape() {
        FieldUiControl uiType = fieldUiType("shape_switch", "形态切换", "string", ViewControlType.TEXT);
        uiType.setValueShape(FieldUiControlValueShape.COMPOSITE);
        uiType.setPrimaryValueKey("value");
        fieldUiTypeService.insert(uiType);

        FieldUiControl update = fieldUiTypeService.select("shape_switch");
        update.setValueShape(FieldUiControlValueShape.SCALAR);
        update.setPrimaryValueKey("value");
        fieldUiTypeService.update(update);

        assertThat(fieldUiTypeService.select("shape_switch").getPrimaryValueKey()).isNull();
    }

    @Test
    void shouldRegisterFieldUiControlsAsReferenceTargets() {
        StaticAbilityCatalog catalog = new StaticAbilityCatalog(java.util.List.of(fieldUiTypeService));

        assertThat(catalog.findReference(ReferenceTargets.fromModuleAlias(FieldUiControlService.MODULE_ALIAS)))
                .containsSame(fieldUiTypeService);
    }

    @Test
    void shouldManageFieldUiControlsWithAttributesAndFieldMappings() {
        FieldUiControl uiType = fieldUiType("date_time_with_time_zone", "日期时间（含时区）",
                "zoned_datetime", ViewControlType.DATETIME);
        uiType.setValueShape(FieldUiControlValueShape.COMPOSITE);
        uiType.setPrimaryValueKey("dateTime");
        String uiTypeId = fieldUiTypeService.insert(uiType);
        fieldUiTypeAttributeService.insert(fieldUiTypeAttribute("date_time_with_time_zone", "format", "格式",
                "string", "YYYY-MM-DD HH:mm:ss"));
        fieldUiTypeFieldMappingService.insert(fieldUiTypeMapping("date_time_with_time_zone", "timeZone", "时区"));

        FieldUiControl saved = fieldUiTypeService.select(uiTypeId);
        assertThat(saved.getAlias()).isEqualTo("date_time_with_time_zone");
        assertThat(saved.getDefaultFieldSpecAlias()).isEqualTo("zoned_datetime");
        assertThat(saved.getRendererType()).isEqualTo(ViewControlType.DATETIME);
        assertThat(fieldUiTypeAttributeService.list(Criteria.of().eq("fieldUiControlAlias", "date_time_with_time_zone"),
                new PageRequest(0, 10), Sort.asc("sortOrder")))
                .extracting(FieldUiControlProperty::getAttributeAlias)
                .containsExactly("format");
        assertThat(fieldUiTypeFieldMappingService.list(Criteria.of().eq("fieldUiControlAlias", "date_time_with_time_zone"),
                new PageRequest(0, 10), Sort.asc("sortOrder")))
                .extracting(FieldUiControlBinding::getValueKey)
                .containsExactly("timeZone");
    }

    @Test
    void shouldPersistAndPopulateFieldUiControlChildrenThroughStandardCrud() {
        FieldUiControlService aggregateService = new FieldUiControlService(
                fieldUiTypeDao, fieldTypeService, null);
        PlatformAbilityRuntime.configureChildAbilityResolver(request -> {
            if (FieldUiControlProperty.class.equals(request.staticModel())) {
                return Optional.of(fieldUiTypeAttributeService);
            }
            if (FieldUiControlBinding.class.equals(request.staticModel())) {
                return Optional.of(fieldUiTypeFieldMappingService);
            }
            return Optional.empty();
        });
        try {
            FieldSpec string = fieldType("aggregate_string", FieldType.STRING, 128);
            fieldTypeService.insert(string);
            FieldUiControl control = fieldUiType("aggregate_control", "组合控件", "aggregate_string",
                    ViewControlType.TEXT);
            control.setValueShape(FieldUiControlValueShape.COMPOSITE);
            control.setPrimaryValueKey("value");
            FieldUiControlProperty property = fieldUiTypeAttribute(null, "placeholder", "占位提示",
                    "aggregate_string", null);
            FieldUiControlBinding binding = fieldUiTypeMapping(null, "value", "值");
            binding.setValueFieldSpecAlias("aggregate_string");
            control.setProperties(List.of(property));
            control.setBindings(List.of(binding));

            String id = aggregateService.insert(control);
            FieldUiControl saved = aggregateService.select(id);

            assertThat(saved.getProperties()).extracting(FieldUiControlProperty::getAttributeAlias)
                    .containsExactly("placeholder");
            assertThat(saved.getBindings()).extracting(FieldUiControlBinding::getValueKey)
                    .containsExactly("value");
            assertThat(saved.getProperties().getFirst().getFieldUiControlAlias()).isEqualTo(id);
            assertThat(saved.getBindings().getFirst().getFieldUiControlAlias()).isEqualTo(id);
            String originalBindingId = saved.getBindings().getFirst().getId();

            saved.setTitle("更新后的组合控件");
            saved.setBindings(List.of());
            aggregateService.update(saved);

            FieldUiControl updated = aggregateService.select(id);
            assertThat(updated.getTitle()).isEqualTo("更新后的组合控件");
            assertThat(updated.getProperties()).hasSize(1);
            assertThat(updated.getBindings()).isEmpty();

            FieldUiControlBinding replacement = fieldUiTypeMapping(null, "value", "恢复后的值");
            replacement.setValueFieldSpecAlias("aggregate_string");
            updated.setBindings(List.of(replacement));
            aggregateService.update(updated);

            FieldUiControl restored = aggregateService.select(id);
            assertThat(restored.getBindings()).singleElement()
                    .satisfies(value -> {
                        assertThat(value.getId()).isEqualTo(originalBindingId);
                        assertThat(value.getTitle()).isEqualTo("恢复后的值");
                        assertThat(value.getDeleted()).isFalse();
                    });

            restored.setValueShape(FieldUiControlValueShape.SCALAR);
            restored.setPrimaryValueKey("value");
            aggregateService.update(restored);

            FieldUiControl scalar = aggregateService.select(id);
            assertThat(scalar.getPrimaryValueKey()).isNull();
            assertThat(scalar.getBindings()).isEmpty();
            assertThat(fieldUiTypeFieldMappingService.selectIgnoreSoftDeleteIfPossible(originalBindingId).getDeleted())
                    .isTrue();
        } finally {
            PlatformAbilityRuntime.resetChildAbilityResolver();
        }
    }

    @Test
    void shouldReconcileMissingSemanticDefaultsForPlatformFieldUiControls() {
        FieldUiControl historicText = new FieldUiControl();
        historicText.setId("text");
        historicText.setAlias("text");
        historicText.setTitle("输入框");
        historicText.setValueShape(FieldUiControlValueShape.SCALAR);
        fieldUiTypeService.insert(historicText);

        PlatformFieldCatalogInitialDataDeclarationProvider provider =
                new PlatformFieldCatalogInitialDataDeclarationProvider(
                        fieldTypeService, fieldUiTypeService, fieldUiTypeAttributeService, fieldUiTypeFieldMappingService);
        new InitialDataExecutor(List.of(), List.of(provider)).initializeAll();

        FieldUiControl text = fieldUiTypeService.requireFieldUiControl("text");
        assertThat(text.getDefaultFieldSpecAlias()).isEqualTo("string");
        assertThat(text.getRendererType()).isEqualTo(ViewControlType.TEXT);
        assertThat(text.getValueShape()).isEqualTo(FieldUiControlValueShape.SCALAR);

        FieldUiControl dateRange = fieldUiTypeService.requireFieldUiControl("date_range");
        assertThat(dateRange.getDefaultFieldSpecAlias()).isEqualTo("date");
        assertThat(dateRange.getPrimaryValueKey()).isEqualTo("start");
        assertThat(dateRange.getQueryMode()).isEqualTo(FieldUiControlQueryMode.BETWEEN);

        Integer textVersion = text.getVersion();
        new InitialDataExecutor(List.of(), List.of(provider)).initializeAll();
        assertThat(fieldUiTypeService.requireFieldUiControl("text").getVersion()).isEqualTo(textVersion);
    }

    @Test
    void shouldRequireTypedComponentsForCompositeFieldUiControls() {
        fieldUiTypeService.insert(fieldUiType("text_input", "输入框", "string", ViewControlType.TEXT));
        FieldUiControlBinding scalarBinding = fieldUiTypeMapping("text_input", "label", "标签");

        assertThatThrownBy(() -> fieldUiTypeFieldMappingService.insert(scalarBinding))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("require COMPOSITE value shape");

        FieldUiControl composite = fieldUiType("date_range", "日期区间", "date", ViewControlType.DATE);
        composite.setValueShape(FieldUiControlValueShape.COMPOSITE);
        composite.setPrimaryValueKey("start");
        fieldUiTypeService.insert(composite);
        FieldUiControlBinding invalidSpec = fieldUiTypeMapping("date_range", "end", "结束值");
        invalidSpec.setValueFieldSpecAlias("missing_spec");

        assertThatThrownBy(() -> fieldUiTypeFieldMappingService.insert(invalidSpec))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Field spec requires existing type");
    }

    @Test
    void shouldRejectUnknownFieldTypeOnFieldUiControlAttribute() {
        fieldUiTypeService.insert(fieldUiType("text_input", "输入框", "string", ViewControlType.TEXT));
        FieldUiControlProperty attribute = fieldUiTypeAttribute("text_input", "maxLength", "字数限制",
                "missing_type", null);

        assertThatThrownBy(() -> fieldUiTypeAttributeService.insert(attribute))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Field spec requires existing type");
    }

    @Test
    void shouldExposePresetFieldUiControlBusinessGranularity() {
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .extracting(FieldUiControl::getAlias)
                .contains("text", "textarea", "file_size", "amount", "select", "multi_select",
                        "date_time_with_time_zone");
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(control -> "file_size".equals(control.getAlias()))
                .singleElement()
                .satisfies(control -> {
                    assertThat(control.getDefaultFieldSpecAlias()).isEqualTo("long");
                    assertThat(control.getValueShape()).isEqualTo(FieldUiControlValueShape.SCALAR);
                });
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(control -> "multi_select".equals(control.getAlias()))
                .extracting(FieldUiControl::getValueShape)
                .containsExactly(FieldUiControlValueShape.COLLECTION);
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(control -> "date_time_with_time_zone".equals(control.getAlias()))
                .extracting(FieldUiControl::getValueShape)
                .containsExactly(FieldUiControlValueShape.COMPOSITE);
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(control -> "date_range".equals(control.getAlias()))
                .extracting(FieldUiControl::getPrimaryValueKey)
                .containsExactly("start");
        assertThat(FieldUiControlPresetCatalog.properties())
                .anySatisfy(attribute -> {
                    assertThat(attribute.getFieldUiControlAlias()).isEqualTo("date_time_with_time_zone");
                    assertThat(attribute.getAttributeAlias()).isEqualTo("format");
                });
        assertThat(FieldUiControlPresetCatalog.bindings())
                .anySatisfy(mapping -> {
                    assertThat(mapping.getFieldUiControlAlias()).isEqualTo("date_time_with_time_zone");
                    assertThat(mapping.getValueKey()).isEqualTo("timeZone");
                });
    }

    @Test
    void shouldEnableOnlyPresetControlsWithPublishedWebFormRenderers() {
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(FieldUiControl::getEnabled)
                .extracting(FieldUiControl::getRendererType)
                .containsOnlyElementsOf(FieldUiControlPresetCatalog.WEB_FORM_EXECUTABLE_RENDERERS);
        assertThat(FieldUiControlPresetCatalog.fieldUiControls())
                .filteredOn(control -> "date_range".equals(control.getAlias())
                        || "date_time_range".equals(control.getAlias())
                        || "date_time_with_time_zone".equals(control.getAlias()))
                .allSatisfy(control -> assertThat(control.getEnabled()).isFalse());
    }

    @Test
    void shouldCompileDefaultUiTypeFromFieldType() {
        fieldUiTypeService.insert(fieldUiType("text", "输入框", "string", ViewControlType.TEXT));
        FieldSpec displayString = fieldType("display_string", FieldType.STRING, 128);
        displayString.setDefaultUiControlAlias("text");
        displayString.setUiControlAliases(java.util.Set.of("text"));
        fieldTypeService.insert(displayString);
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "displayName", "display_name", FieldType.STRING);
        field.setFieldSpecAlias("display_string");
        fieldService.insert(field);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);

        assertThat(definition.defaultUiControlAlias()).isEqualTo("text");
    }

    @Test
    void shouldCompileDictionaryBindingOnMetadataField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY));
        MetadataField field = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDictionaryCategoryAlias("customer_status");
        fieldConfigService.insert(config);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(config.getDictionaryApplicationAlias()).isEqualTo("crm");
        assertThat(definition.dictionaryBinding().applicationAlias()).isEqualTo("crm");
        assertThat(definition.dictionaryBinding().categoryAlias()).isEqualTo("customer_status");
        assertThat(definition.dictionaryBinding().selectionMode()).isEqualTo(OptionSelectionMode.SINGLE);
        assertThat(definition.queryDefinition().queryable()).isTrue();
        assertThat(definition.queryDefinition().defaultOperator()).isEqualTo(DynamicQueryOperator.LIKE);
    }

    @Test
    void shouldCompileMultipleDictionaryBindingOnJsonMetadataField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_tag", DictionaryCategoryKind.DICTIONARY));
        MetadataField field = field(metadataId, "tags", "tags", FieldType.JSON);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDictionaryCategoryAlias("customer_tag");
        config.setSelectionMode(OptionSelectionMode.MULTIPLE);
        fieldConfigService.insert(config);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);

        assertThat(definition.type()).isEqualTo(FieldType.JSON);
        assertThat(definition.dictionaryBinding().categoryAlias()).isEqualTo("customer_tag");
        assertThat(definition.dictionaryBinding().selectionMode()).isEqualTo(OptionSelectionMode.MULTIPLE);
    }

    @Test
    void shouldOverrideFieldShapeWithoutChangingFieldTypeCatalog() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "shortCode", "short_code", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setFieldLength(32);
        fieldConfigService.insert(config);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);
        assertThat(definition.type()).isEqualTo(FieldType.STRING);
        assertThat(definition.length()).isEqualTo(32);
        assertThat(definition.queryDefinition().queryable()).isTrue();
    }

    @Test
    void shouldOverrideFieldBehaviorInRelationScope() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(field);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        MetadataFieldConfig defaultConfig = fieldConfig(field.getId());
        defaultConfig.setFieldLength(64);
        fieldConfigService.insert(defaultConfig);
        MetadataFieldConfig override = fieldConfig(field.getId());
        override.setRelationId(relationId);
        override.setQueryable(false);
        fieldConfigService.insert(override);

        FieldDefinition defaultDefinition = fieldDefinitionCompiler.compile(field);
        FieldDefinition scopedDefinition = fieldDefinitionCompiler.compile(field, relationId);

        assertThat(defaultDefinition.queryDefinition().queryable()).isTrue();
        assertThat(scopedDefinition.queryDefinition().queryable()).isFalse();
        assertThat(scopedDefinition.length()).isEqualTo(64);
    }

    @Test
    void shouldCompileFieldBehaviorDefinition() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "code", "code", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDefaultValue("NEW");
        config.setValidationRegex("[A-Z]+");
        config.setCopyable(false);
        config.setWriteProtected(true);
        fieldConfigService.insert(config);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field);

        assertThat(definition.behavior().defaultValue()).isEqualTo("NEW");
        assertThat(definition.behavior().validationRegex()).isEqualTo("[A-Z]+");
        assertThat(definition.behavior().copyable()).isFalse();
        assertThat(definition.behavior().writeProtected()).isTrue();
    }

    @Test
    void shouldMergeRelationScopedFieldBehaviorByProperty() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "code", "code", FieldType.STRING);
        fieldService.insert(field);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        MetadataFieldConfig defaultConfig = fieldConfig(field.getId());
        defaultConfig.setDefaultValue("NEW");
        defaultConfig.setValidationRegex("[A-Z]+");
        fieldConfigService.insert(defaultConfig);
        MetadataFieldConfig relationConfig = fieldConfig(field.getId());
        relationConfig.setRelationId(relationId);
        relationConfig.setCopyable(false);
        fieldConfigService.insert(relationConfig);

        FieldDefinition definition = fieldDefinitionCompiler.compile(field, relationId);

        assertThat(definition.behavior().defaultValue()).isEqualTo("NEW");
        assertThat(definition.behavior().validationRegex()).isEqualTo("[A-Z]+");
        assertThat(definition.behavior().copyable()).isFalse();
        assertThat(definition.behavior().writeProtected()).isFalse();
    }

    @Test
    void shouldRejectInvalidMergedFieldBehaviorDefinition() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "code", "code", FieldType.STRING);
        fieldService.insert(field);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        MetadataFieldConfig defaultConfig = fieldConfig(field.getId());
        defaultConfig.setDefaultValue("abc");
        fieldConfigService.insert(defaultConfig);
        MetadataFieldConfig relationConfig = fieldConfig(field.getId());
        relationConfig.setRelationId(relationId);
        relationConfig.setValidationRegex("[A-Z]+");
        fieldConfigService.insert(relationConfig);

        assertThatThrownBy(() -> fieldDefinitionCompiler.compile(field, relationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValue");
    }

    @Test
    void shouldRejectRegexBehaviorOnNonStringField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "amount", "amount", FieldType.INTEGER);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setValidationRegex("[0-9]+");

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationRegex");
    }

    @Test
    void shouldRejectInvalidFieldBehaviorDefaultValue() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField code = field(metadataId, "code", "code", FieldType.STRING);
        fieldService.insert(code);
        MetadataFieldConfig regexMismatch = fieldConfig(code.getId());
        regexMismatch.setDefaultValue("abc");
        regexMismatch.setValidationRegex("[A-Z]+");
        assertThatThrownBy(() -> fieldConfigService.insert(regexMismatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValue");

        MetadataField enabled = field(metadataId, "enabled", "enabled", FieldType.BOOLEAN);
        fieldService.insert(enabled);
        MetadataFieldConfig invalidBoolean = fieldConfig(enabled.getId());
        invalidBoolean.setDefaultValue("abc");
        assertThatThrownBy(() -> fieldConfigService.insert(invalidBoolean))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boolean defaultValue");
    }

    @Test
    void shouldRejectRelationScopedPhysicalShapeOverride() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "shortCode", "short_code", FieldType.STRING);
        fieldService.insert(field);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setRelationId(relationId);
        config.setFieldLength(32);

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("physical field shape");
    }

    @Test
    void shouldRejectFieldShapeThatDoesNotMatchFieldType() {
        FieldSpec invalidType = fieldType("integer_length", FieldType.INTEGER, 32);
        assertThatThrownBy(() -> fieldTypeService.insert(invalidType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length only applies");

        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "amount", "amount", FieldType.INTEGER);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setFieldLength(32);

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length only applies");
    }

    @Test
    void shouldRejectDictionaryBindingOnNonStringField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY));
        MetadataField field = field(metadataId, "status", "status", FieldType.INTEGER);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDictionaryCategoryAlias("customer_status");

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionary binding");
    }

    @Test
    void shouldRejectMultipleDictionaryBindingOnNonJsonField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        categoryService.insert(category("crm", "customer_tag", DictionaryCategoryKind.DICTIONARY));
        MetadataField field = field(metadataId, "tags", "tags", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDictionaryCategoryAlias("customer_tag");
        config.setSelectionMode(OptionSelectionMode.MULTIPLE);

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple dictionary binding requires JSON field");
    }

    @Test
    void shouldRejectDictionaryBindingWithoutExistingCategory() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField field = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(field);
        MetadataFieldConfig config = fieldConfig(field.getId());
        config.setDictionaryCategoryAlias("customer_status");

        assertThatThrownBy(() -> fieldConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("existing category");
    }

    @Test
    void shouldCreateMetadataFieldReferenceConfig() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        fieldService.insert(titleField(customerId));
        fieldService.insert(field(customerId, "code", "code", FieldType.STRING));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setProjectionMappings("title:customerIdTitle,code:customerCode");

        String id = referenceConfigService.insert(config);

        MetadataFieldReferenceConfig saved = referenceConfigService.select(id);
        assertThat(saved.getCardinality()).isEqualTo(net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE);
        assertThat(saved.projections()).anySatisfy(projection -> assertThat(projection.outputField()).isEqualTo("customerIdTitle"));
        assertThat(saved.projections()).hasSize(2);
    }

    @Test
    void shouldRefreshDynamicRuntimeWhenFieldReferenceConfigChanges() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        fieldService.insert(titleField(customerId));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        clearInvocations(runtimeRefreshCoordinator);

        referenceConfigService.insert(referenceConfig(customerField.getId(), customerId));

        verify(runtimeRefreshCoordinator).refreshByMetadataField(argThat(refreshed ->
                refreshed != null && customerField.getId().equals(refreshed.getId())));
    }

    @Test
    void shouldRejectReferenceConfigForNonStringSourceField() {
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.INTEGER);
        fieldService.insert(customerField);
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source field must be string");
    }

    @Test
    void shouldRejectCrossModuleReferenceLoadConfig() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setTargetModuleAlias("crm.customer");
        config.setProjectionMappings("title:customerIdTitle");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void shouldRequireRelationScopedCrossModuleReferenceConfig() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String orderId = metadataService.insert(metadata("crm", "order"));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        MetadataField customerField = field(orderId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        relationService.insert(mainRelation("crm.customer", customerId));
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setTargetModuleAlias("crm.customer");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("relation-scoped");
    }

    @Test
    void shouldRejectSameModuleReferenceTargetThatIsNotBoundToSourceModule() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        String orderId = metadataService.insert(metadata("crm", "order"));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        MetadataField customerField = field(orderId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        relationService.insert(mainRelation("crm.order", orderId));

        assertThatThrownBy(() -> referenceConfigService.insert(referenceConfig(customerField.getId(), customerId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("target metadata is not bound to module");
    }

    @Test
    void shouldRequireCrossModuleReferenceTargetToBeModuleMainMetadata() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String orderId = metadataService.insert(metadata("crm", "order"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        MetadataField contactField = field(orderId, "contactId", "contact_id", FieldType.STRING);
        fieldService.insert(contactField);
        String orderRelationId = relationService.insert(mainRelation("crm.order", orderId));
        MetadataFieldReferenceConfig config = referenceConfig(contactField.getId(), contactId);
        config.setRelationId(orderRelationId);
        config.setTargetModuleAlias("crm.customer");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("target module MAIN metadata");
    }

    @Test
    void shouldCreateRelationScopedCrossModuleReferenceConfigToModuleMainMetadata() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String orderId = metadataService.insert(metadata("crm", "order"));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        MetadataField customerField = field(orderId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        String orderRelationId = relationService.insert(mainRelation("crm.order", orderId));
        relationService.insert(mainRelation("crm.customer", customerId));
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setRelationId(orderRelationId);
        config.setTargetModuleAlias("crm.customer");

        String id = referenceConfigService.insert(config);

        assertThat(referenceConfigService.select(id))
                .extracting(MetadataFieldReferenceConfig::getTargetModuleAlias,
                        MetadataFieldReferenceConfig::getTargetMetadataId)
                .containsExactly("crm.customer", customerId);
    }

    @Test
    void shouldRejectReferenceTitleOutputConflictWithSourceField() {
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        fieldService.insert(titleField(customerId));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        fieldService.insert(field(contactId, "customerTitle", "customer_title", FieldType.STRING));
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setProjectionMappings("title:customerTitle");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference projection output field conflicts with source field");
    }

    @Test
    void shouldRejectReferenceProjectionOutputConflictWithStandardField() {
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        fieldService.insert(field(customerId, "code", "code", FieldType.STRING));
        MetadataField customerField = field(contactId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerField);
        MetadataFieldReferenceConfig config = referenceConfig(customerField.getId(), customerId);
        config.setProjectionMappings("code:id");

        assertThatThrownBy(() -> referenceConfigService.insert(config))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference projection output field conflicts with source field");
    }

    @Test
    void shouldCreateDynamicModuleMainMetadataAsOneOrchestrationOperation() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));

        ModuleMainMetadataCreationResult result = orchestrationService.createMainMetadata("crm.customer",
                new ModuleMainMetadataCreateCommand("customer", "客户", null, null, true));

        assertThat(result.metadata().getApplicationAlias()).isEqualTo("crm");
        assertThat(result.metadata().getAlias()).isEqualTo("customer");
        assertThat(result.metadata().getTableName()).isEqualTo("crm_customer");
        assertThat(result.metadata().getDataScopeEnabled()).isTrue();
        assertThat(result.relation().getModuleAlias()).isEqualTo("crm.customer");
        assertThat(result.relation().getMetadataId()).isEqualTo(result.metadata().getId());
        assertThat(result.relation().getRelationRole()).isEqualTo(RelationRole.MAIN);
    }

    @Test
    void shouldRejectMainMetadataOrchestrationForStaticOrAlreadyBoundModule() {
        moduleService.insert(module("crm.static_customer", "crm", ModuleKind.STATIC));
        assertThatThrownBy(() -> orchestrationService.createMainMetadata("crm.static_customer",
                new ModuleMainMetadataCreateCommand("customer", "客户", null, null, false)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only supports dynamic module");

        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        orchestrationService.createMainMetadata("crm.customer",
                new ModuleMainMetadataCreateCommand("customer", "客户", null, null, false));

        assertThatThrownBy(() -> orchestrationService.createMainMetadata("crm.customer",
                new ModuleMainMetadataCreateCommand("contact", "联系人", null, null, false)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already has MAIN metadata relation");
    }

    @Test
    void shouldCreateRelationScopedMetadataViewFields() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        fieldService.insert(titleField(metadataId));
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        fieldService.insert(status);
        fieldUiTypeService.insert(fieldUiType("select", "下拉单选", "string", ViewControlType.SELECT));
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        MetadataView view = metadataView(relationId, EntityViewType.LIST);
        String viewId = viewService.insert(view);
        MetadataViewField viewField = metadataViewField(viewId, status.getId());
        viewField.setFieldUiControlAlias("select");
        viewField.setReadOnly(true);
        viewField.setRequiredOverride(true);

        viewFieldService.insert(viewField);

        EntityViewFieldDefinition definition = viewFieldService.compile(viewField);
        assertThat(viewService.listByRelationIds(List.of(relationId))).extracting(MetadataView::getId)
                .containsExactly(viewId);
        assertThat(definition.fieldName()).isEqualTo("status");
        assertThat(definition.controlType()).isEqualTo(ViewControlType.SELECT);
        assertThat(definition.fieldUiControlAlias()).isEqualTo("select");
        assertThat(definition.readOnly()).isTrue();
        assertThat(definition.required()).isTrue();
    }

    @Test
    void shouldRejectViewFieldUiControlOutsideFieldTypeAllowedSet() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField status = field(metadataId, "status", "status", FieldType.STRING);
        status.setFieldSpecAlias("string");
        fieldService.insert(status);
        fieldUiTypeService.insert(fieldUiType("amount", "金额", "decimal", ViewControlType.DECIMAL));
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        String viewId = viewService.insert(metadataView(relationId, EntityViewType.FORM));
        MetadataViewField viewField = metadataViewField(viewId, status.getId());
        viewField.setFieldUiControlAlias("amount");

        assertThatThrownBy(() -> viewFieldService.insert(viewField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("default field type mismatch");
    }

    @Test
    void shouldRejectViewFieldOutsideRelationMetadata() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerId = metadataService.insert(metadata("crm", "customer"));
        String contactId = metadataService.insert(metadata("crm", "contact"));
        fieldService.insert(titleField(customerId));
        MetadataField contactTitle = titleField(contactId);
        fieldService.insert(contactTitle);
        String relationId = relationService.insert(mainRelation("crm.customer", customerId));
        String viewId = viewService.insert(metadataView(relationId, EntityViewType.FORM));

        assertThatThrownBy(() -> viewFieldService.insert(metadataViewField(viewId, contactTitle.getId())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("metadata mismatch");
    }

    @Test
    void shouldRejectViewFieldThatRelaxesRequiredMetadataField() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField code = field(metadataId, "code", "code", FieldType.STRING);
        code.setRequired(true);
        fieldService.insert(code);
        String relationId = relationService.insert(mainRelation("crm.customer", metadataId));
        String viewId = viewService.insert(metadataView(relationId, EntityViewType.FORM));
        MetadataViewField viewField = metadataViewField(viewId, code.getId());
        viewField.setRequiredOverride(false);

        assertThatThrownBy(() -> viewFieldService.insert(viewField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot make required");
    }

    @Test
    void shouldRejectFieldWithoutExistingMetadata() {
        MetadataField field = field("missing", "code", "code", FieldType.STRING);

        assertThatThrownBy(() -> fieldService.insert(field))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("existing metadata");
    }

    @Test
    void shouldRejectDuplicateFieldNameColumnNameAndSingleTitleField() {
        String metadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField code = field(metadataId, "code", "code", FieldType.STRING);
        code.setTitleField(true);
        fieldService.insert(code);

        assertThatThrownBy(() -> fieldService.insert(field(metadataId, "code", "customer_code", FieldType.STRING)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("fieldName");
        assertThatThrownBy(() -> fieldService.insert(field(metadataId, "customerCode", "code", FieldType.STRING)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("columnName");

        MetadataField name = field(metadataId, "name", "name", FieldType.STRING);
        name.setTitleField(true);
        assertThatThrownBy(() -> fieldService.insert(name))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("title field");
    }

    @Test
    void shouldBindMainMetadataAndRejectDuplicateMainRelation() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));

        relationService.insert(mainRelation("crm.customer", customerMetadataId));

        assertThatThrownBy(() -> relationService.insert(mainRelation("crm.customer", profileMetadataId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("one MAIN");
    }

    @Test
    void shouldRejectDuplicateRelationAliasWithinModule() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        String noteMetadataId = metadataService.insert(metadata("crm", "note"));
        relationService.insert(mainRelation("crm.customer", customerMetadataId));
        relationService.insert(childRelation("crm.customer", profileMetadataId, customerMetadataId));
        ModuleMetadataRelation duplicateAlias = childRelation("crm.customer", noteMetadataId, customerMetadataId);

        assertThatThrownBy(() -> relationService.insert(duplicateAlias))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("relationAlias");
    }

    @Test
    void shouldRequireMainRelationBeforeDynamicChildRelation() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        ModuleMetadataRelation child = childRelation("crm.customer", profileMetadataId, customerMetadataId);

        assertThatThrownBy(() -> relationService.insert(child))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parent metadata relation");
    }

    @Test
    void shouldBindChildRelationAfterMainRelation() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataRelation child = childRelation("crm.customer", profileMetadataId, customerMetadataId);

        relationService.insert(child);

        assertThat(relationService.list(Criteria.of().eq("moduleAlias", "crm.customer"), PageRequest.of(1, 10)))
                .extracting(ModuleMetadataRelation::getRelationRole)
                .containsExactly(RelationRole.MAIN, RelationRole.CHILD);
    }

    @Test
    void shouldRequireChildRelationForeignKeyToBeExistingPhysicalFieldAndKeepDataScopeOnMainOnly() {
        ModuleMetadataRelationService validatingRelationService = new ModuleMetadataRelationService(
                relationDao, moduleService, metadataService, Optional.empty(), Optional.of(fieldService));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        validatingRelationService.insert(mainRelation("crm.customer", customerMetadataId));

        ModuleMetadataRelation missingField = childRelation("crm.customer", profileMetadataId, customerMetadataId);
        assertThatThrownBy(() -> validatingRelationService.insert(missingField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("physical foreign key field");

        fieldService.insert(field(profileMetadataId, "customerId", "customer_id", FieldType.STRING));
        Metadata scopedChild = metadataService.select(profileMetadataId);
        scopedChild.setDataScopeEnabled(Boolean.TRUE);
        metadataService.update(scopedChild);

        assertThatThrownBy(() -> validatingRelationService.insert(
                childRelation("crm.customer", profileMetadataId, customerMetadataId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot enable module data scope");
    }

    @Test
    void shouldRejectSortPartitionConfigurationOnChildMetadata() {
        ModuleMetadataRelationService validatingRelationService = new ModuleMetadataRelationService(
                relationDao, moduleService, metadataService, Optional.empty(), Optional.of(fieldService));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        validatingRelationService.insert(mainRelation("crm.customer", customerMetadataId));
        fieldService.insert(field(profileMetadataId, "customerId", "customer_id", FieldType.STRING));
        Metadata profile = metadataService.select(profileMetadataId);
        profile.setSortPartitionFields(java.util.Set.of("customer_id"));
        metadataService.update(profile);

        assertThatThrownBy(() -> validatingRelationService.insert(
                childRelation("crm.customer", profileMetadataId, customerMetadataId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot configure sort partition fields");
    }

    @Test
    void shouldEnsureAndResolveRelationScopedFields() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField title = titleField(customerMetadataId);
        title.setSortOrder(10);
        fieldService.insert(title);
        MetadataField status = field(customerMetadataId, "status", "status", FieldType.STRING);
        status.setSortOrder(20);
        fieldService.insert(status);
        String relationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));

        List<ModuleMetadataField> fields = moduleFieldService.ensureForRelation(relationId);

        assertThat(fields).extracting(ModuleMetadataField::getMetadataFieldId)
                .containsExactly(title.getId(), status.getId());
        ResolvedModuleMetadataField resolved = moduleFieldService.resolve(fields.get(1).getId());
        assertThat(resolved.moduleAlias()).isEqualTo("crm.customer");
        assertThat(resolved.relationId()).isEqualTo(relationId);
        assertThat(resolved.relationAlias()).isEqualTo("customer");
        assertThat(resolved.metadataAlias()).isEqualTo("customer");
        assertThat(resolved.fieldName()).isEqualTo("status");
        assertThat(resolved.fieldSpecAlias()).isEqualTo("string");
    }

    @Test
    void shouldSaveModuleScopedFieldBehaviorAndReferenceConfig() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField customerId = field(customerMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        String relationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId(customerId.getId());
        moduleField.setDefaultValue("guest");
        moduleField.setCloneable(true);
        moduleField.setValidationRegex("[a-z]+");
        moduleField.setDictionaryApplicationAlias("crm");
        moduleField.setDictionaryCategoryAlias("customer_level");
        moduleField.setReferenceModuleAlias("crm.customer");
        moduleField.setReferenceModuleKeyField("id");
        moduleField.setReferenceModuleLabelField("title");
        moduleField.setReferenceModulePlusFields(java.util.Set.of("code", "ownerName"));

        String id = moduleFieldService.insert(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(id);
        assertThat(saved.getDefaultValue()).isEqualTo("guest");
        assertThat(saved.getCloneable()).isTrue();
        assertThat(saved.getReferenceModuleAlias()).isEqualTo("crm.customer");
        assertThat(saved.getReferenceModulePlusFields()).containsExactlyInAnyOrder("code", "ownerName");
    }

    @Test
    void shouldValidateReferenceGenerateRuleDirectionWhenValidatorIsAvailable() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        String orderMetadataId = metadataService.insert(metadata("crm", "order"));
        MetadataField customerId = field(orderMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        String relationId = relationService.insert(mainRelation("crm.order", orderMetadataId));
        ModuleMetadataFieldService validatingService = new ModuleMetadataFieldService(
                new MemoryDao<>(),
                relationService,
                metadataService,
                fieldService,
                Optional.of((ruleId, referenceModuleAlias, ownerModuleAlias) -> {
                    if (!"generate-order".equals(ruleId)
                            || !"crm.customer".equals(referenceModuleAlias)
                            || !"crm.order".equals(ownerModuleAlias)) {
                        throw new PlatformException("invalid direction");
                    }
                }));
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId(customerId.getId());
        moduleField.setReferenceModuleAlias("crm.customer");
        moduleField.setReferenceModuleKeyField("id");
        moduleField.setReferenceModuleLabelField("title");
        moduleField.setReferenceGenerateRuleId("generate-order");

        String id = validatingService.insert(moduleField);

        assertThat(validatingService.select(id).getReferenceGenerateRuleId()).isEqualTo("generate-order");

        ModuleMetadataField invalid = new ModuleMetadataField();
        invalid.setRelationId(relationId);
        invalid.setMetadataFieldId(customerId.getId());
        invalid.setReferenceModuleAlias("crm.customer");
        invalid.setReferenceModuleKeyField("id");
        invalid.setReferenceModuleLabelField("title");
        invalid.setReferenceGenerateRuleId("wrong-rule");
        assertThatThrownBy(() -> validatingService.insert(invalid))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("invalid direction");
    }

    @Test
    void shouldRejectReferenceDependentConfigWithoutReferenceModule() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField customerId = field(customerMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        String relationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId(customerId.getId());
        moduleField.setReferenceModuleKeyField("id");

        assertThatThrownBy(() -> moduleFieldService.insert(moduleField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("referenceModuleAlias");
    }

    @Test
    void shouldSaveReferenceFiltersAndAffectsAroundModuleMetadataField() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String orderMetadataId = metadataService.insert(metadata("crm", "order"));
        MetadataField customerId = field(orderMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        MetadataField customerRegion = field(orderMetadataId, "customerRegion", "customer_region", FieldType.STRING);
        fieldService.insert(customerRegion);
        String orderRelationId = relationService.insert(mainRelation("crm.order", orderMetadataId));
        List<ModuleMetadataField> orderFields = moduleFieldService.ensureForRelation(orderRelationId);
        ModuleMetadataField owner = moduleField(orderFields, customerId.getId());
        owner.setReferenceModuleAlias("crm.customer");
        owner.setReferenceModuleKeyField("id");
        owner.setReferenceModuleLabelField("title");
        moduleFieldService.update(owner);
        ModuleMetadataField formRegion = moduleField(orderFields, customerRegion.getId());

        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField title = titleField(customerMetadataId);
        fieldService.insert(title);
        MetadataField region = field(customerMetadataId, "region", "region", FieldType.STRING);
        fieldService.insert(region);
        String customerRelationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        List<ModuleMetadataField> customerFields = moduleFieldService.ensureForRelation(customerRelationId);
        ModuleMetadataField referenceRegion = moduleField(customerFields, region.getId());
        ModuleMetadataField referenceTitle = moduleField(customerFields, title.getId());

        ModuleMetadataFieldFilter filter = new ModuleMetadataFieldFilter();
        filter.setModuleMetadataFieldId(owner.getId());
        filter.setFormFieldId(formRegion.getId());
        filter.setReferenceFieldId(referenceRegion.getId());
        filter.setOperator(DynamicQueryOperator.EQ);
        moduleFieldFilterService.insert(filter);
        ModuleMetadataFieldAffect affect = new ModuleMetadataFieldAffect();
        affect.setModuleMetadataFieldId(owner.getId());
        affect.setReferenceFieldId(referenceTitle.getId());
        affect.setTargetFieldId(formRegion.getId());
        moduleFieldAffectService.insert(affect);

        assertThat(moduleFieldFilterService.list(Criteria.of().eq("moduleMetadataFieldId", owner.getId()), PageRequest.of(1, 10)))
                .hasSize(1);
        assertThat(moduleFieldAffectService.list(Criteria.of().eq("moduleMetadataFieldId", owner.getId()), PageRequest.of(1, 10)))
                .hasSize(1);
    }

    @Test
    void shouldRejectReferenceFilterOrAffectOnNonReferenceModuleMetadataField() {
        moduleService.insert(module("crm.order", "crm", ModuleKind.DYNAMIC));
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String orderMetadataId = metadataService.insert(metadata("crm", "order"));
        MetadataField customerId = field(orderMetadataId, "customerId", "customer_id", FieldType.STRING);
        fieldService.insert(customerId);
        MetadataField customerRegion = field(orderMetadataId, "customerRegion", "customer_region", FieldType.STRING);
        fieldService.insert(customerRegion);
        String orderRelationId = relationService.insert(mainRelation("crm.order", orderMetadataId));
        List<ModuleMetadataField> orderFields = moduleFieldService.ensureForRelation(orderRelationId);
        ModuleMetadataField owner = moduleField(orderFields, customerId.getId());
        ModuleMetadataField formRegion = moduleField(orderFields, customerRegion.getId());

        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField region = field(customerMetadataId, "region", "region", FieldType.STRING);
        fieldService.insert(region);
        String customerRelationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataField referenceRegion = moduleField(
                moduleFieldService.ensureForRelation(customerRelationId), region.getId());

        ModuleMetadataFieldFilter filter = new ModuleMetadataFieldFilter();
        filter.setModuleMetadataFieldId(owner.getId());
        filter.setFormFieldId(formRegion.getId());
        filter.setReferenceFieldId(referenceRegion.getId());
        assertThatThrownBy(() -> moduleFieldFilterService.insert(filter))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("referenceModuleAlias");

        ModuleMetadataFieldAffect affect = new ModuleMetadataFieldAffect();
        affect.setModuleMetadataFieldId(owner.getId());
        affect.setReferenceFieldId(referenceRegion.getId());
        affect.setTargetFieldId(formRegion.getId());
        assertThatThrownBy(() -> moduleFieldAffectService.insert(affect))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("referenceModuleAlias");
    }

    @Test
    void shouldSaveMeasureUnitConfigWithCompanionAndShadowFields() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
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
        moduleField.setUnitFieldId(quantityUnit.getId());
        moduleField.setBaseValueFieldId(quantityBase.getId());
        moduleField.setDefaultUnitCode("box");
        moduleField.setUnitRequired(true);

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        assertThat(saved.getUnitMode()).isEqualTo(FieldMeasureUnitMode.SELECTABLE);
        assertThat(saved.getUnitConversionMode()).isEqualTo(FieldMeasureUnitConversionMode.LINEAR);
        assertThat(saved.getUnitFieldId()).isEqualTo(quantityUnit.getId());
        assertThat(saved.getBaseValueFieldId()).isEqualTo(quantityBase.getId());
        assertThat(fieldService.select(quantityBase.getId()).getSystemManaged()).isTrue();
    }

    @Test
    void shouldSaveMeasureUnitConfigAndEnsureCompanionAndShadowFields() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setUnitConversionMode(FieldMeasureUnitConversionMode.LINEAR);
        moduleField.setUnitRequired(true);
        clearInvocations(schemaEnsureService, runtimeRefreshCoordinator);

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        MetadataField unitField = fieldService.select(saved.getUnitFieldId());
        MetadataField baseValueField = fieldService.select(saved.getBaseValueFieldId());
        assertThat(unitField.getFieldName()).isEqualTo("quantityUnit");
        assertThat(unitField.getColumnName()).isEqualTo("quantity_unit");
        assertThat(unitField.getFieldForm()).isEqualTo(MetadataFieldForm.COMPANION);
        assertThat(unitField.getFieldRole()).isEqualTo(MetadataFieldRole.MEASURE_UNIT);
        assertThat(unitField.getOwnerFieldId()).isEqualTo(quantity.getId());
        assertThat(baseValueField.getFieldName()).isEqualTo("quantityBase");
        assertThat(baseValueField.getColumnName()).isEqualTo("quantity_base");
        assertThat(baseValueField.getFieldForm()).isEqualTo(MetadataFieldForm.SHADOW);
        assertThat(baseValueField.getFieldRole()).isEqualTo(MetadataFieldRole.MEASURE_BASE_VALUE);
        assertThat(baseValueField.getSystemManaged()).isTrue();
        assertThat(saved.getUnitCategoryAlias()).isEqualTo("package");
        assertThat(saved.getBaseUnitCategoryAlias()).isEqualTo("package");
        assertThat(saved.getBaseUnitCode()).isEqualTo("bottle");
        assertThat(moduleFieldService.listByRelationId(relationId))
                .extracting(ModuleMetadataField::getMetadataFieldId)
                .contains(quantity.getId(), unitField.getId(), baseValueField.getId());
        EntityDefinition schemaEntity = metadataEntityDefinitionCompiler.compile(metadataId);
        assertThat(schemaEntity.fields()).extracting(FieldDefinition::fieldName)
                .contains("quantity", "quantityUnit", "quantityBase");
        FieldDefinition runtimeField = fieldDefinitionCompiler.compile(quantity, relationId, saved);
        assertThat(runtimeField.measureUnit().enabled()).isTrue();
        assertThat(runtimeField.measureUnit().categoryAlias()).isEqualTo("package");
        assertThat(runtimeField.measureUnit().mode()).isEqualTo(FieldMeasureUnitMode.SELECTABLE);
        assertThat(runtimeField.measureUnit().defaultUnitCode()).isEqualTo("box");
        assertThat(runtimeField.measureUnit().unitFieldName()).isEqualTo("quantityUnit");
        assertThat(runtimeField.measureUnit().baseValueFieldName()).isEqualTo("quantityBase");
        assertThat(runtimeField.measureUnit().baseUnitCategoryAlias()).isEqualTo("package");
        assertThat(runtimeField.measureUnit().baseUnitCode()).isEqualTo("bottle");
        assertThat(runtimeField.measureUnit().conversionMode()).isEqualTo(FieldMeasureUnitConversionMode.LINEAR);
        assertThat(runtimeField.measureUnit().unitRequired()).isTrue();
        verify(schemaEnsureService, atLeastOnce()).ensure(metadataId);
        verify(runtimeRefreshCoordinator, atLeastOnce()).refreshByModuleField(argThat(
                refreshed -> refreshed != null && moduleField.getId().equals(refreshed.getId())));
    }

    @Test
    void shouldReuseEnsuredMeasureUnitFieldsWithoutDuplicatingMetadataFields() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");
        moduleField.setBaseUnitCode("bottle");

        moduleFieldService.update(moduleField);
        ModuleMetadataField first = moduleFieldService.select(moduleField.getId());
        moduleFieldService.update(first);
        ModuleMetadataField second = moduleFieldService.select(moduleField.getId());

        assertThat(second.getUnitFieldId()).isEqualTo(first.getUnitFieldId());
        assertThat(second.getBaseValueFieldId()).isEqualTo(first.getBaseValueFieldId());
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("quantity", "quantityUnit", "quantityBase");
    }

    @Test
    void shouldSaveFixedMeasureUnitWithoutCompanionUnitField() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setUnitMode(FieldMeasureUnitMode.FIXED);
        moduleField.setFixedUnitCode("box");
        moduleField.setBaseUnitCode("bottle");

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        assertThat(saved.getUnitMode()).isEqualTo(FieldMeasureUnitMode.FIXED);
        assertThat(saved.getFixedUnitCode()).isEqualTo("box");
        assertThat(saved.getUnitFieldId()).isNull();
        assertThat(fieldService.select(saved.getBaseValueFieldId()).getFieldName()).isEqualTo("quantityBase");
    }

    @Test
    void shouldRejectMeasureUnitSaveBeforeCreatingFieldsWhenConfigIsInvalid() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .hasMessageContaining("baseUnitCode");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("quantity");
    }

    @Test
    void shouldRejectMeasureUnitSaveWhenRelatedFieldsAreDuplicated() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        MetadataField firstUnit = field(metadataId, "quantityUnit", "quantity_unit", FieldType.STRING);
        firstUnit.setFieldForm(MetadataFieldForm.COMPANION);
        firstUnit.setFieldRole(MetadataFieldRole.MEASURE_UNIT);
        firstUnit.setOwnerFieldId(quantity.getId());
        fieldService.insert(firstUnit);
        MetadataField secondUnit = field(metadataId, "quantityUnit2", "quantity_unit2", FieldType.STRING);
        secondUnit.setFieldForm(MetadataFieldForm.COMPANION);
        secondUnit.setFieldRole(MetadataFieldRole.MEASURE_UNIT);
        secondUnit.setOwnerFieldId(quantity.getId());
        fieldService.insert(secondUnit);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");
        moduleField.setBaseUnitCode("bottle");

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must be unique");
    }

    @Test
    void shouldRejectMeasureUnitSaveBeforeCreatingFieldsWhenConversionScopeIsInvalid() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        String otherMetadataId = metadataService.insert(metadata("sales", "sku"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        MetadataField skuCode = field(otherMetadataId, "skuCode", "sku_code", FieldType.STRING);
        fieldService.insert(skuCode);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setConversionScopeFieldId(skuCode.getId());

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .hasMessageContaining("conversionScopeFieldId must belong to same metadata");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("quantity");
    }

    @Test
    void shouldRejectMeasureUnitConfigWithoutCompanionUnitField() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        MetadataField unit = field(metadataId, "quantityUnit", "quantity_unit", FieldType.STRING);
        fieldService.insert(unit);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setUnitFieldId(unit.getId());

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unitFieldId requires COMPANION MEASURE_UNIT");
    }

    @Test
    void shouldEnsureMeasureUnitBaseValueShadowFieldWhenMissing() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        MetadataField quantityUnit = field(metadataId, "quantityUnit", "quantity_unit", FieldType.STRING);
        quantityUnit.setFieldForm(MetadataFieldForm.COMPANION);
        quantityUnit.setFieldRole(MetadataFieldRole.MEASURE_UNIT);
        quantityUnit.setOwnerFieldId(quantity.getId());
        fieldService.insert(quantityUnit);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setUnitFieldId(quantityUnit.getId());

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        assertThat(fieldService.select(saved.getBaseValueFieldId()).getFieldName()).isEqualTo("quantityBase");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistMeasureUnitValuesThroughRuntimeEntityCompiledFromSavedMetadata() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order_line"));
        MetadataField quantity = field(metadataId, "quantity", "quantity", FieldType.DECIMAL);
        fieldService.insert(quantity);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), quantity.getId());
        moduleField.setUnitCategoryAlias("package");
        moduleField.setDefaultUnitCode("box");
        moduleField.setBaseUnitCode("bottle");
        moduleField.setUnitConversionMode(FieldMeasureUnitConversionMode.LINEAR);
        moduleField.setUnitRequired(true);
        moduleFieldService.update(moduleField);

        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(MetadataService.DEFAULT_SCHEMA);
        when(operations.insertItem(eq(MetadataService.DEFAULT_SCHEMA), eq("sales_order_line"), anyMap(),
                eq(StandardEntitySchema.ID_COLUMN))).thenReturn("line-1");
        DynamicRecordService recordService = dynamicRecordService(
                operations,
                moduleDefinitionCompiler().compile("sales.order"),
                measureUnitCoordinator()
        );

        DynamicRecord record = recordService.newRecord("sales.order", "order_line")
                .setValue("quantity", new BigDecimal("2"));
        recordService.create("sales.order", "order_line", record);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(operations).insertItem(eq(MetadataService.DEFAULT_SCHEMA), eq("sales_order_line"), body.capture(),
                eq(StandardEntitySchema.ID_COLUMN));
        assertThat(body.getValue())
                .containsEntry("quantity", new BigDecimal("2"))
                .containsEntry("quantity_unit", "box");
        assertThat((BigDecimal) body.getValue().get("quantity_base")).isEqualByComparingTo("24");
    }

    @Test
    void shouldSaveMoneyConfigAndEnsureCompanionAndShadowFields() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        MetadataField orderDate = field(metadataId, "orderDate", "order_date", FieldType.DATE);
        fieldService.insert(orderDate);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        clearInvocations(schemaEnsureService, runtimeRefreshCoordinator);

        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyDefaultCurrencyCode("usd");
        moduleField.setMoneyBaseCurrencyCode("cny");
        moduleField.setMoneyRateTypeCode("spot");
        moduleField.setMoneyRateDateFieldId(orderDate.getId());
        moduleField.setMoneyCurrencyRequired(true);

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        MetadataField currencyField = fieldService.select(saved.getMoneyCurrencyFieldId());
        MetadataField baseAmountField = fieldService.select(saved.getMoneyBaseAmountFieldId());
        assertThat(currencyField.getFieldName()).isEqualTo("amountCurrency");
        assertThat(currencyField.getColumnName()).isEqualTo("amount_currency");
        assertThat(currencyField.getFieldForm()).isEqualTo(MetadataFieldForm.COMPANION);
        assertThat(currencyField.getFieldRole()).isEqualTo(MetadataFieldRole.MONEY_CURRENCY);
        assertThat(currencyField.getOwnerFieldId()).isEqualTo(amount.getId());
        assertThat(baseAmountField.getFieldName()).isEqualTo("amountBase");
        assertThat(baseAmountField.getFieldRole()).isEqualTo(MetadataFieldRole.MONEY_BASE_AMOUNT);
        assertThat(baseAmountField.getSystemManaged()).isTrue();
        assertThat(saved.getMoneyCurrencyMode()).isEqualTo(FieldMoneyMode.SELECTABLE);
        assertThat(saved.getMoneyDefaultCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getMoneyBaseCurrencyCode()).isEqualTo("CNY");
        assertThat(saved.getMoneyRateTypeCode()).isEqualTo("SPOT");
        assertThat(saved.getMoneyRateDateFieldId()).isEqualTo(orderDate.getId());
        assertThat(saved.getMoneyCurrencyFieldId()).isEqualTo(currencyField.getId());
        assertThat(saved.getMoneyBaseAmountFieldId()).isEqualTo(baseAmountField.getId());
        assertThat(saved.getMoneyExchangeRateFieldId()).isNull();
        assertThat(moduleFieldService.listByRelationId(relationId))
                .extracting(ModuleMetadataField::getMetadataFieldId)
                .contains(amount.getId(), orderDate.getId(), currencyField.getId(), baseAmountField.getId());
        EntityDefinition schemaEntity = metadataEntityDefinitionCompiler.compile(metadataId);
        assertThat(schemaEntity.fields()).extracting(FieldDefinition::fieldName)
                .contains("amount", "orderDate", "amountCurrency", "amountBase");
        FieldDefinition runtimeField = fieldDefinitionCompiler.compile(amount, relationId, saved);
        assertThat(runtimeField.money().enabled()).isTrue();
        assertThat(runtimeField.money().currencyMode()).isEqualTo(FieldMoneyMode.SELECTABLE);
        assertThat(runtimeField.money().defaultCurrencyCode()).isEqualTo("USD");
        assertThat(runtimeField.money().currencyFieldName()).isEqualTo("amountCurrency");
        assertThat(runtimeField.money().baseAmountFieldName()).isEqualTo("amountBase");
        assertThat(runtimeField.money().baseCurrencyCode()).isEqualTo("CNY");
        assertThat(runtimeField.money().rateTypeCode()).isEqualTo("SPOT");
        assertThat(runtimeField.money().rateDateFieldName()).isEqualTo("orderDate");
        assertThat(runtimeField.money().exchangeRateFieldName()).isNull();
        assertThat(runtimeField.money().currencyRequired()).isTrue();
        verify(schemaEnsureService, atLeastOnce()).ensure(metadataId);
        verify(runtimeRefreshCoordinator, atLeastOnce()).refreshByModuleField(argThat(
                refreshed -> refreshed != null && moduleField.getId().equals(refreshed.getId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistMoneyValuesThroughRuntimeEntityCompiledFromSavedMetadata() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        MetadataField orderDate = field(metadataId, "orderDate", "order_date", FieldType.DATE);
        fieldService.insert(orderDate);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyDefaultCurrencyCode("USD");
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");
        moduleField.setMoneyRateDateFieldId(orderDate.getId());
        moduleFieldService.update(moduleField);

        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(MetadataService.DEFAULT_SCHEMA);
        when(operations.insertItem(eq(MetadataService.DEFAULT_SCHEMA), eq("sales_order"), anyMap(),
                eq(StandardEntitySchema.ID_COLUMN))).thenReturn("order-1");
        DynamicRecordService recordService = dynamicRecordService(
                operations,
                moduleDefinitionCompiler().compile("sales.order"),
                moneyCoordinator()
        );

        DynamicRecord record = recordService.newRecord("sales.order", "order")
                .setValue("amount", new BigDecimal("2"))
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        recordService.create("sales.order", "order", record);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(operations).insertItem(eq(MetadataService.DEFAULT_SCHEMA), eq("sales_order"), body.capture(),
                eq(StandardEntitySchema.ID_COLUMN));
        assertThat(body.getValue())
                .containsEntry("amount", new BigDecimal("2"))
                .containsEntry("amount_currency", "USD")
                .containsEntry("order_date", LocalDate.of(2026, 2, 16));
        assertThat((BigDecimal) body.getValue().get("amount_base")).isEqualByComparingTo("14.47");
    }

    @Test
    void shouldSaveMoneyConfigWithExplicitExchangeRateShadowField() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        MetadataField exchangeRate = field(metadataId, "amountExchangeRate", "amount_exchange_rate", FieldType.DECIMAL);
        exchangeRate.setFieldForm(MetadataFieldForm.SHADOW);
        exchangeRate.setFieldRole(MetadataFieldRole.MONEY_EXCHANGE_RATE);
        exchangeRate.setOwnerFieldId(amount.getId());
        fieldService.insert(exchangeRate);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");
        moduleField.setMoneyExchangeRateFieldId(exchangeRate.getId());

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        assertThat(saved.getMoneyExchangeRateFieldId()).isEqualTo(exchangeRate.getId());
        assertThat(moduleFieldService.listByRelationId(relationId))
                .extracting(ModuleMetadataField::getMetadataFieldId)
                .contains(exchangeRate.getId());
        FieldDefinition compiled = fieldDefinitionCompiler.compile(amount, relationId, saved);
        assertThat(compiled.money().exchangeRateFieldName()).isEqualTo("amountExchangeRate");
    }

    @Test
    void shouldReuseEnsuredMoneyFieldsWithoutDuplicatingMetadataFields() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");

        moduleFieldService.update(moduleField);
        ModuleMetadataField first = moduleFieldService.select(moduleField.getId());
        moduleFieldService.update(first);
        ModuleMetadataField second = moduleFieldService.select(moduleField.getId());

        assertThat(second.getMoneyCurrencyFieldId()).isEqualTo(first.getMoneyCurrencyFieldId());
        assertThat(second.getMoneyBaseAmountFieldId()).isEqualTo(first.getMoneyBaseAmountFieldId());
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("amount", "amountCurrency", "amountBase");
    }

    @Test
    void shouldSaveFixedMoneyWithoutCompanionCurrencyField() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());

        moduleField.setMoneyCurrencyMode(FieldMoneyMode.FIXED);
        moduleField.setMoneyFixedCurrencyCode("usd");
        moduleField.setMoneyBaseCurrencyCode("cny");
        moduleField.setMoneyRateTypeCode("spot");

        moduleFieldService.update(moduleField);

        ModuleMetadataField saved = moduleFieldService.select(moduleField.getId());
        assertThat(saved.getMoneyCurrencyFieldId()).isNull();
        assertThat(saved.getMoneyExchangeRateFieldId()).isNull();
        assertThat(fieldService.select(saved.getMoneyBaseAmountFieldId()).getFieldName()).isEqualTo("amountBase");
        assertThat(saved.getMoneyCurrencyMode()).isEqualTo(FieldMoneyMode.FIXED);
        assertThat(saved.getMoneyFixedCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getMoneyDefaultCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void shouldCompileSavedMoneyConfigIntoFieldDefinition() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyDefaultCurrencyCode("USD");
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");
        moduleFieldService.update(moduleField);

        FieldDefinition compiled = fieldDefinitionCompiler.compile(amount, relationId,
                moduleFieldService.select(moduleField.getId()));

        assertThat(compiled.money().enabled()).isTrue();
        assertThat(compiled.money().currencyMode()).isEqualTo(FieldMoneyMode.SELECTABLE);
        assertThat(compiled.money().currencyFieldName()).isEqualTo("amountCurrency");
        assertThat(compiled.money().baseAmountFieldName()).isEqualTo("amountBase");
        assertThat(compiled.money().baseCurrencyCode()).isEqualTo("CNY");
        assertThat(compiled.money().rateTypeCode()).isEqualTo("SPOT");
        assertThat(compiled.money().exchangeRateFieldName()).isNull();
    }

    @Test
    void shouldRejectMoneySaveBeforeCreatingFieldsWhenConfigIsInvalid() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());

        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyBaseCurrencyCode("CNY");

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .hasMessageContaining("moneyRateTypeCode");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("amount");
    }

    @Test
    void shouldRejectMoneySaveBeforeCreatingFieldsWhenRateDateFieldIsInvalid() {
        moduleService.insert(module("sales.order", "sales", ModuleKind.DYNAMIC));
        String metadataId = metadataService.insert(metadata("sales", "order"));
        MetadataField amount = field(metadataId, "amount", "amount", FieldType.DECIMAL);
        fieldService.insert(amount);
        MetadataField description = field(metadataId, "description", "description", FieldType.STRING);
        fieldService.insert(description);
        String relationId = relationService.insert(mainRelation("sales.order", metadataId));
        ModuleMetadataField moduleField = moduleField(moduleFieldService.ensureForRelation(relationId), amount.getId());
        moduleField.setMoneyCurrencyMode(FieldMoneyMode.SELECTABLE);
        moduleField.setMoneyBaseCurrencyCode("CNY");
        moduleField.setMoneyRateTypeCode("SPOT");
        moduleField.setMoneyRateDateFieldId(description.getId());

        assertThatThrownBy(() -> moduleFieldService.update(moduleField))
                .hasMessageContaining("money rate date field requires date or timestamp field");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadataId), PageRequest.of(1, 20)))
                .extracting(MetadataField::getFieldName)
                .containsExactly("amount", "description");
    }

    @Test
    void shouldRejectRelationFieldOutsideRelationMetadata() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        String profileMetadataId = metadataService.insert(metadata("crm", "profile"));
        MetadataField profileName = titleField(profileMetadataId);
        fieldService.insert(profileName);
        String relationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataField node = new ModuleMetadataField();
        node.setRelationId(relationId);
        node.setMetadataFieldId(profileName.getId());

        assertThatThrownBy(() -> moduleFieldService.insert(node))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("field in relation metadata");
    }

    @Test
    void shouldRejectDuplicateRelationField() {
        moduleService.insert(module("crm.customer", "crm", ModuleKind.DYNAMIC));
        String customerMetadataId = metadataService.insert(metadata("crm", "customer"));
        MetadataField title = titleField(customerMetadataId);
        fieldService.insert(title);
        String relationId = relationService.insert(mainRelation("crm.customer", customerMetadataId));
        ModuleMetadataField first = new ModuleMetadataField();
        first.setRelationId(relationId);
        first.setMetadataFieldId(title.getId());
        moduleFieldService.insert(first);
        ModuleMetadataField duplicate = new ModuleMetadataField();
        duplicate.setRelationId(relationId);
        duplicate.setMetadataFieldId(title.getId());

        assertThatThrownBy(() -> moduleFieldService.insert(duplicate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must be unique");
    }

    private ModuleMetadataField moduleField(List<ModuleMetadataField> fields, String metadataFieldId) {
        return fields.stream()
                .filter(field -> field.getMetadataFieldId().equals(metadataFieldId))
                .findFirst()
                .orElseThrow();
    }

    private ModuleMetadataField moduleField(String relationId, String metadataFieldId) {
        ModuleMetadataField field = new ModuleMetadataField();
        field.setRelationId(relationId);
        field.setMetadataFieldId(metadataFieldId);
        return field;
    }

    private Metadata metadata(String applicationAlias, String alias) {
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(alias);
        metadata.setTitle(alias);
        return metadata;
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

    private FieldSpec fieldType(String alias, FieldType fieldType, Integer length) {
        FieldSpec type = new FieldSpec();
        type.setAlias(alias);
        type.setTitle(alias);
        type.setFieldType(fieldType);
        type.setDefaultLength(length);
        type.setDefaultQueryOperator(DynamicQueryOperator.defaultOperator(fieldType));
        type.setQueryOperators(DynamicQueryOperator.names(DynamicQueryOperator.defaultOperators(fieldType)));
        return type;
    }

    private FieldUiControl fieldUiType(String alias,
                                            String title,
                                            String defaultFieldSpecAlias,
                                            ViewControlType controlType) {
        FieldUiControl type = new FieldUiControl();
        type.setAlias(alias);
        type.setTitle(title);
        type.setDefaultFieldSpecAlias(defaultFieldSpecAlias);
        type.setRendererType(controlType);
        return type;
    }

    private FieldUiControlProperty fieldUiTypeAttribute(String fieldUiControlAlias,
                                                              String attributeAlias,
                                                              String title,
                                                              String valueFieldSpecAlias,
                                                              String defaultValue) {
        FieldUiControlProperty attribute = new FieldUiControlProperty();
        attribute.setFieldUiControlAlias(fieldUiControlAlias);
        attribute.setAttributeAlias(attributeAlias);
        attribute.setTitle(title);
        attribute.setValueFieldSpecAlias(valueFieldSpecAlias);
        attribute.setDefaultValue(defaultValue);
        return attribute;
    }

    private FieldUiControlBinding fieldUiTypeMapping(String fieldUiControlAlias, String valueKey, String title) {
        FieldUiControlBinding mapping = new FieldUiControlBinding();
        mapping.setFieldUiControlAlias(fieldUiControlAlias);
        mapping.setValueKey(valueKey);
        mapping.setValueFieldSpecAlias("string");
        mapping.setTitle(title);
        return mapping;
    }

    private MetadataFieldConfig fieldConfig(String fieldId) {
        MetadataFieldConfig config = new MetadataFieldConfig();
        config.setMetadataFieldId(fieldId);
        return config;
    }

    private MetadataFieldProtectionConfig protectionConfig(String fieldId) {
        MetadataFieldProtectionConfig config = new MetadataFieldProtectionConfig();
        config.setMetadataFieldId(fieldId);
        return config;
    }

    private MetadataFieldReferenceConfig referenceConfig(String fieldId, String targetMetadataId) {
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(fieldId);
        config.setTargetMetadataId(targetMetadataId);
        return config;
    }

    private MetadataView metadataView(String relationId, EntityViewType viewType) {
        MetadataView view = new MetadataView();
        view.setRelationId(relationId);
        view.setViewType(viewType);
        return view;
    }

    private MetadataViewField metadataViewField(String viewId, String fieldId) {
        MetadataViewField viewField = new MetadataViewField();
        viewField.setViewId(viewId);
        viewField.setMetadataFieldId(fieldId);
        return viewField;
    }

    private DictionaryCategory category(String applicationAlias, String alias, DictionaryCategoryKind kind) {
        DictionaryCategory category = new DictionaryCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setCategoryKind(kind);
        category.setTitle(alias);
        return category;
    }

    private Currency currency(String code, String numericCode, String title, String symbol, int scale) {
        Currency currency = new Currency();
        currency.setCode(code);
        currency.setNumericCode(numericCode);
        currency.setTitle(title);
        currency.setSymbol(symbol);
        currency.setDecimalScale(scale);
        return currency;
    }

    private ExchangeRateType exchangeRateType(String code, String title) {
        ExchangeRateType rateType = new ExchangeRateType();
        rateType.setCode(code);
        rateType.setTitle(title);
        return rateType;
    }

    private ExchangeRate exchangeRate(String from, String to, String type, String effectiveDate, String rateValue) {
        ExchangeRate rate = new ExchangeRate();
        rate.setFromCurrencyCode(from);
        rate.setToCurrencyCode(to);
        rate.setRateTypeCode(type);
        rate.setEffectiveDate(LocalDate.parse(effectiveDate));
        rate.setRate(new BigDecimal(rateValue));
        rate.setTitle(from + "/" + to + " " + type);
        return rate;
    }

    private MoneyDynamicRecordMutationCoordinator moneyCoordinator() {
        CurrencyService currencyService = new CurrencyService(new MemoryDao<>());
        ExchangeRateTypeService rateTypeService = new ExchangeRateTypeService(new MemoryDao<>());
        ExchangeRateService rateService = new ExchangeRateService(new MemoryDao<>(), currencyService, rateTypeService);
        TenantCurrencySettingService tenantCurrencySettingService =
                new TenantCurrencySettingService(new MemoryDao<>(), currencyService);
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        rateTypeService.insert(exchangeRateType("SPOT", "Spot"));
        rateService.insert(exchangeRate("USD", "CNY", "SPOT", "2026-02-01", "7.2345"));
        return new MoneyDynamicRecordMutationCoordinator(
                new CurrencyConversionService(currencyService, rateService),
                tenantCurrencySettingService,
                Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private MeasureUnitDynamicRecordMutationCoordinator measureUnitCoordinator() {
        MeasureUnitCategoryService categoryService = new MeasureUnitCategoryService(new MemoryDao<>());
        MeasureUnitService unitService = new MeasureUnitService(new MemoryDao<>(), categoryService);
        MeasureUnitConversionRuleService ruleService = new MeasureUnitConversionRuleService(new MemoryDao<>(), unitService);
        categoryService.insert(measureUnitCategory("sales", "package", MeasureDimension.COUNT, "bottle"));
        unitService.insert(measureUnit("sales", "package", "bottle", BigDecimal.ONE));
        unitService.insert(measureUnit("sales", "package", "box", new BigDecimal("12")));
        return new MeasureUnitDynamicRecordMutationCoordinator(
                new MeasureUnitConversionService(categoryService, unitService),
                new MeasureUnitBusinessConversionService(unitService, ruleService),
                Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private MeasureUnitCategory measureUnitCategory(String applicationAlias,
                                                    String alias,
                                                    MeasureDimension dimension,
                                                    String baseUnitCode) {
        MeasureUnitCategory category = new MeasureUnitCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setDimension(dimension);
        category.setBaseUnitCode(baseUnitCode);
        category.setTitle(alias);
        return category;
    }

    private MeasureUnit measureUnit(String applicationAlias,
                                    String categoryAlias,
                                    String code,
                                    BigDecimal factorToBase) {
        MeasureUnit unit = new MeasureUnit();
        unit.setApplicationAlias(applicationAlias);
        unit.setCategoryAlias(categoryAlias);
        unit.setCode(code);
        unit.setTitle(code);
        unit.setFactorToBase(factorToBase);
        return unit;
    }

    private PlatformModuleDefinitionCompiler moduleDefinitionCompiler() {
        PlatformModuleActionService actionService =
                new PlatformModuleActionService(new MemoryDao<PlatformModuleAction>(), moduleService);
        ModuleMetadataFormulaRuleService formulaRuleService =
                new ModuleMetadataFormulaRuleService(new MemoryDao<>(), relationService, fieldService);
        return new PlatformModuleDefinitionCompiler(
                moduleService,
                metadataService,
                fieldService,
                fieldDefinitionCompiler,
                referenceConfigService,
                relationService,
                viewService,
                viewFieldService,
                actionService,
                formulaRuleService,
                moduleFieldService,
                moduleFieldFilterService,
                moduleFieldAffectService,
                new ModuleDefinitionValidator()
        );
    }

    private DynamicRecordService dynamicRecordService(IDatabaseOperations<Object> operations,
                                                      ModuleDefinition definition,
                                                      MoneyDynamicRecordMutationCoordinator mutationCoordinator) {
        return dynamicRecordService(operations, definition, (DynamicRecordMutationCoordinator) mutationCoordinator);
    }

    private DynamicRecordService dynamicRecordService(IDatabaseOperations<Object> operations,
                                                      ModuleDefinition definition,
                                                      MeasureUnitDynamicRecordMutationCoordinator mutationCoordinator) {
        return dynamicRecordService(operations, definition, (DynamicRecordMutationCoordinator) mutationCoordinator);
    }

    private DynamicRecordService dynamicRecordService(
            IDatabaseOperations<Object> operations,
            ModuleDefinition definition,
            DynamicRecordMutationCoordinator mutationCoordinator) {
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .build()
                .register(definition);
        return new DynamicRecordService(
                runtime,
                new AllowAllActionExecutionPolicyService(),
                new AllowAllDataScopeCriteriaService(),
                mutationCoordinator
        );
    }

    private PlatformModule module(String alias, String applicationAlias, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(applicationAlias);
        module.setModuleKind(kind);
        module.setTitle(alias);
        return module;
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
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setParentMetadataId(parentMetadataId);
        relation.setRelationRole(RelationRole.CHILD);
        relation.setForeignKey("customerId");
        relation.setRelationAlias("profile");
        relation.setTitle("profile");
        return relation;
    }

    private static class MemoryDao<T extends EntityContract> implements BaseDao<T, String> {
        private final Map<String, T> rows = new LinkedHashMap<>();

        @Override
        public boolean ensureTable() {
            return true;
        }

        @Override
        public String insert(T entity) {
            rows.put(entity.getId(), entity);
            return entity.getId();
        }

        @Override
        public int updateById(T entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int updateByIdAndCondition(T entity, Map<String, Object> conditions) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public int deleteByIdAndCondition(String id, Map<String, Object> conditions) {
            return deleteById(id);
        }

        @Override
        public boolean existsById(String id) {
            return rows.containsKey(id);
        }

        @Override
        public T findById(String id) {
            return rows.get(id);
        }

        @Override
        public List<T> query(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<T> filtered = rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(this::sortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            int from = Math.min(pageRequest.getOffset(), filtered.size());
            int to = Math.min(from + pageRequest.getLimit(), filtered.size());
            return new ArrayList<>(filtered.subList(from, to));
        }

        @Override
        public List<T> list(Criteria criteria, Sort... sorts) {
            return rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(this::sortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        }

        @Override
        public PageResult<T> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<T> records = query(criteria, pageRequest, sorts);
            return PageResult.of(records, records.size(), pageRequest);
        }

        @Override
        public long count(Criteria criteria) {
            return rows.values().stream().filter(row -> matches(row, criteria)).count();
        }

        @Override
        public int upsert(T entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        private Integer sortOrder(T row) {
            return row instanceof SortCapable sortable ? sortable.getSortOrder() : null;
        }

        private boolean matches(T row, Criteria criteria) {
            if (criteria == null || criteria.isEmpty()) {
                return true;
            }
            return matchesGroup(row, criteria.getRoot());
        }

        private boolean matchesGroup(T row, CriteriaGroup group) {
            Boolean matched = null;
            for (CriteriaGroup.Entry entry : group.getEntries()) {
                boolean entryMatched = matchesNode(row, entry.getNode());
                if (matched == null) {
                    matched = entryMatched;
                } else if (isOrJoin(entry)) {
                    matched = matched || entryMatched;
                } else {
                    matched = matched && entryMatched;
                }
            }
            return matched == null || matched;
        }

        private boolean matchesNode(T row, Object node) {
            if (node instanceof CriteriaClause clause) {
                return matchesClause(row, clause);
            }
            if (node instanceof CriteriaGroup group) {
                return matchesGroup(row, group);
            }
            return true;
        }

        private boolean matchesClause(T row, CriteriaClause clause) {
            Object actual = value(row, clause.getField());
            if (clause.getOperator() == CriteriaOperator.IS_NULL) {
                return actual == null;
            }
            if (clause.getOperator() == CriteriaOperator.IS_NOT_NULL) {
                return actual != null;
            }
            if (clause.getOperator() == CriteriaOperator.EQ) {
                Object expected = clause.getValues().getFirst();
                return expected == null ? actual == null : expected.equals(actual);
            }
            return true;
        }

        private Object value(T row, String field) {
            try {
                String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                return row.getClass().getMethod(getter).invoke(row);
            } catch (ReflectiveOperationException e) {
                try {
                    String getter = "is" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                    return row.getClass().getMethod(getter).invoke(row);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }

        private boolean isOrJoin(CriteriaGroup.Entry entry) {
            try {
                Method method = entry.getClass().getMethod("getJoin");
                return "OR".equals(String.valueOf(method.invoke(entry)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read criteria join", e);
            }
        }
    }
}
