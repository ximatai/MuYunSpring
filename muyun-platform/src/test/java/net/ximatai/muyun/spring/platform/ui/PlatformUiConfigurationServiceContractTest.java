package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformUiConfigurationServiceContractTest {
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final TestMemoryDao<Metadata> metadataDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataField> fieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataFieldConfig> fieldConfigDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataField> moduleFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<FieldSpec> fieldTypeDao = new TestMemoryDao<>();
    private final TestMemoryDao<FieldUiControl> fieldUiTypeDao = new TestMemoryDao<>();
    private final TestMemoryDao<FieldUiControlProperty> fieldUiTypeAttributeDao = new TestMemoryDao<>();
    private final TestMemoryDao<FieldUiControlBinding> fieldUiTypeFieldMappingDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiSet> uiSetDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiConfig> uiConfigDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiConfigField> uiConfigFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformQueryTemplate> queryTemplateDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformQueryItem> queryItemDao = new TestMemoryDao<>();

    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MetadataService metadataService = new MetadataService(metadataDao);
    private final FieldSpecService fieldTypeService = new FieldSpecService(fieldTypeDao, fieldUiTypeDao);
    private final FieldUiControlService fieldUiTypeService =
            new FieldUiControlService(fieldUiTypeDao, fieldTypeService);
    private final FieldUiControlPropertyService fieldUiTypeAttributeService =
            new FieldUiControlPropertyService(fieldUiTypeAttributeDao, fieldUiTypeService, fieldTypeService);
    private final FieldUiControlBindingService fieldUiTypeFieldMappingService =
            new FieldUiControlBindingService(fieldUiTypeFieldMappingDao, fieldUiTypeService, fieldTypeService);
    private final MetadataFieldService fieldService = new MetadataFieldService(fieldDao, metadataService, fieldTypeService);
    private final ModuleMetadataRelationService relationService =
            new ModuleMetadataRelationService(relationDao, moduleService, metadataService);
    private final MetadataFieldConfigService fieldConfigService = new MetadataFieldConfigService(
            fieldConfigDao, fieldService, metadataService, fieldTypeService, null, relationService);
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler =
            new MetadataFieldDefinitionCompiler(fieldTypeService, fieldConfigService, null, fieldService);
    private final ModuleMetadataFieldService moduleFieldService =
            new ModuleMetadataFieldService(moduleFieldDao, relationService, metadataService, fieldService);
    private final PlatformUiSetService uiSetService = new PlatformUiSetService(uiSetDao, moduleService);
    private final PlatformUiConfigService uiConfigService = new PlatformUiConfigService(uiConfigDao, uiSetService);
    private final PlatformUiConfigFieldService uiConfigFieldService = new PlatformUiConfigFieldService(
            uiConfigFieldDao, uiConfigService, uiSetService, moduleFieldService, fieldTypeService, fieldUiTypeService,
            fieldService);
    private final PlatformQueryTemplateService queryTemplateService =
            new PlatformQueryTemplateService(queryTemplateDao, moduleService);
    private final PlatformQueryItemService queryItemService =
            new PlatformQueryItemService(queryItemDao, queryTemplateService, moduleFieldService, fieldTypeService,
                    fieldDefinitionCompiler, (net.ximatai.muyun.spring.common.time.PlatformTimeService) null);
    private final PlatformPageConfigPublishService publishService = new PlatformPageConfigPublishService(
            uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService);
    private final PlatformPageConfigSnapshotService snapshotService = new PlatformPageConfigSnapshotService(
            uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService);
    private final PlatformUiConfigScaffoldService scaffoldService = new PlatformUiConfigScaffoldService(
            uiSetService, uiConfigService, uiConfigFieldService, moduleFieldService, fieldTypeService,
            fieldUiTypeService);

    @Test
    void shouldCreateUiConfigWithFieldsAndSnapshotByModule() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");

        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        String appDraftConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.APP, false));
        PlatformUiConfigField field = uiField(uiConfigId, customerNameField, "text");
        field.setWidth(180);
        uiConfigFieldService.insert(uiField(appDraftConfigId, customerNameField, "text"));

        uiConfigFieldService.insert(field);
        publishService.publishUiConfig(uiConfigId);

        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot("crm.customer");
        assertThat(snapshot.moduleAlias()).isEqualTo("crm.customer");
        assertThat(snapshot.uiSets()).extracting(PlatformUiSet::getAlias).containsExactly("list");
        assertThat(snapshot.uiConfigs()).extracting(PlatformUiConfig::getUiSetId).containsExactly(uiSetId);
        assertThat(snapshot.uiConfigs()).extracting(PlatformUiConfig::getPublished).containsExactly(Boolean.TRUE);
        assertThat(snapshot.uiFields())
                .extracting(PlatformUiConfigField::getModuleMetadataFieldId)
                .containsExactly(customerNameField);
        assertThat(snapshot.uiFields().getFirst().getFieldUiControlAlias()).isEqualTo("text");
    }

    @Test
    void shouldRejectDisablingUiControlReferencedByUiConfigField() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String moduleFieldId = seedModuleField("crm.customer", "customer", "name", "name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, moduleFieldId, "text"));

        FieldUiControlService protectedService = new FieldUiControlService(
                fieldUiTypeDao, fieldTypeService, uiConfigFieldDao);

        assertThatThrownBy(() -> protectedService.disable("text"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be disabled");
    }

    @Test
    void shouldValidatePortableConditionalUiPredicatesBeforePublish() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String moduleFieldId = seedModuleField("crm.customer", "customer", "category", "category", "string");
        String kindFieldId = addModuleField("crm.customer", "kind", "kind", "string");
        String levelFieldId = addModuleField("crm.customer", "level", "level", "string");
        String statusFieldId = addModuleField("crm.customer", "status", "status", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "form", PlatformUiSetType.FORM, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, kindFieldId, "text"));
        uiConfigFieldService.insert(uiField(uiConfigId, levelFieldId, "text"));
        uiConfigFieldService.insert(uiField(uiConfigId, statusFieldId, "text"));
        PlatformUiConfigField field = uiField(uiConfigId, moduleFieldId, "text");
        field.setVisibleWhen("{kind} == 'company' && IN({level}, 'gold', 'silver')");
        field.setReadOnlyWhen("{status} != 'draft'");

        String fieldId = uiConfigFieldService.insert(field);
        PlatformUiConfigField persisted = uiConfigFieldService.select(fieldId);
        assertThat(persisted.getVisibleWhen()).isEqualTo("{kind} == 'company' && IN({level}, 'gold', 'silver')");
        assertThat(persisted.getReadOnlyWhen()).isEqualTo("{status} != 'draft'");
        PlatformUiConfigField invalid = uiField(uiConfigId, moduleFieldId, "text");
        invalid.setVisibleWhen("window.alert('not portable')");
        assertThatThrownBy(() -> uiConfigFieldService.insert(invalid))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("visibleWhen must be a FormulaEngine WEB_UI predicate");
        PlatformUiConfigField unavailable = uiField(uiConfigId, moduleFieldId, "text");
        unavailable.setReadOnlyWhen("{unconfigured} == true");
        assertThatThrownBy(() -> uiConfigFieldService.insert(unavailable))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("may only reference fields available in the same UI config and relation");
        publishService.publishUiConfig(uiConfigId);
    }

    @Test
    void shouldExposeFieldUiControlMappingsInPageBootstrap() {
        seedFieldType("date", FieldType.DATE, DynamicQueryOperator.BETWEEN);
        seedUiType("date_range", "date");
        seedUiTypeFieldMapping("date_range", "end");
        String signedDateField = seedModuleField("crm.contract", "contract", "signedDate", "signed_date", "date");
        String uiSetId = uiSetService.insert(uiSet("crm.contract", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        PlatformUiConfigField uiField = uiField(uiConfigId, signedDateField, "date_range");
        uiField.setMaxDisplayLines(2);
        uiConfigFieldService.insert(uiField);
        publishService.publishUiConfig(uiConfigId);
        Menu menu = new Menu();
        menu.setId("menu-1");
        menu.setTitle("Contracts");
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias("crm.contract");
        menu.setPageMode(MenuPageMode.LIST);
        MenuService menuService = org.mockito.Mockito.mock(MenuService.class);
        org.mockito.Mockito.when(menuService.currentUserVisibleMenu("menu-1")).thenReturn(menu);
        PlatformPageBootstrapService bootstrapService = new PlatformPageBootstrapService(
                menuService,
                snapshotService,
                moduleFieldService,
                fieldUiTypeService,
                fieldUiTypeAttributeService,
                fieldUiTypeFieldMappingService);

        PlatformPageBootstrap bootstrap = bootstrapService.bootstrapByMenu("menu-1", PlatformUiClientType.WEB);

        assertThat(bootstrap.resolvedConfig().uiFields()).hasSize(1);
        assertThat(bootstrap.resolvedConfig().uiFields().getFirst().fieldUiControlAlias()).isEqualTo("date_range");
        assertThat(bootstrap.resolvedConfig().uiFields().getFirst().maxDisplayLines()).isEqualTo(2);
        assertThat(bootstrap.resolvedConfig().fieldUiControls()).hasSize(1);
        assertThat(bootstrap.resolvedConfig().fieldUiControls().getFirst().alias()).isEqualTo("date_range");
        assertThat(bootstrap.resolvedConfig().fieldUiControls().getFirst().bindings())
                .extracting(PlatformResolvedFieldUiControlBinding::valueKey)
                .containsExactly("end");
        assertThat(bootstrap.resolvedConfig().fieldUiControls().getFirst().bindings())
                .extracting(PlatformResolvedFieldUiControlBinding::valueFieldSpecAlias)
                .containsExactly("date");
    }

    @Test
    void shouldResolveLayoutBlocksOnlyFromTheEntrySelectedUiConfig() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String primarySetId = uiSetService.insert(uiSet("crm.customer", "primary", PlatformUiSetType.LIST, true));
        String alternateSetId = uiSetService.insert(uiSet("crm.customer", "alternate", PlatformUiSetType.LIST, false));
        String primaryConfigId = uiConfigService.insert(uiConfig(primarySetId, PlatformUiClientType.WEB, false));
        String alternateConfigId = uiConfigService.insert(uiConfig(alternateSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(primaryConfigId, customerNameField, "text"));
        uiConfigFieldService.insert(uiField(alternateConfigId, customerNameField, "text"));

        PlatformUiConfig primaryConfig = uiConfigService.select(primaryConfigId);
        primaryConfig.setLayoutJson("""
                {"blocks":[{"type":"action","key":"primary","actionCode":"primaryAction"}]}
                """);
        uiConfigService.update(primaryConfig);
        PlatformUiConfig alternateConfig = uiConfigService.select(alternateConfigId);
        alternateConfig.setLayoutJson("""
                {"blocks":[{"type":"action","key":"alternate","actionCode":"alternateAction"}]}
                """);
        uiConfigService.update(alternateConfig);
        publishService.publishUiConfig(primaryConfigId);
        publishService.publishUiConfig(alternateConfigId);

        Menu menu = new Menu();
        menu.setId("menu-alternate");
        menu.setTitle("Alternate customers");
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias("crm.customer");
        menu.setPageMode(MenuPageMode.LIST);
        menu.setDefaultUiConfigId(alternateConfigId);
        MenuService menuService = org.mockito.Mockito.mock(MenuService.class);
        org.mockito.Mockito.when(menuService.currentUserVisibleMenu("menu-alternate")).thenReturn(menu);
        PlatformPageBootstrapService bootstrapService = new PlatformPageBootstrapService(
                menuService, snapshotService, moduleFieldService, fieldUiTypeService, fieldUiTypeAttributeService,
                fieldUiTypeFieldMappingService);

        PlatformPageBootstrap bootstrap = bootstrapService.bootstrapByMenu("menu-alternate", PlatformUiClientType.WEB);

        assertThat(bootstrap.entry().defaultUiConfigId()).isEqualTo(alternateConfigId);
        assertThat(bootstrap.resolvedConfig().actionBlocks())
                .extracting(PlatformActionBlock::uiConfigId)
                .containsExactly(alternateConfigId);
    }

    @Test
    void shouldExposeVirtualFieldFormInPageBootstrap() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String displayNameField = seedModuleField("crm.customer", "customer", "displayName",
                "display_name", "string", false, MetadataFieldForm.VIRTUAL);
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, displayNameField, "text"));
        publishService.publishUiConfig(uiConfigId);
        Menu menu = new Menu();
        menu.setId("menu-virtual");
        menu.setTitle("Customers");
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias("crm.customer");
        menu.setPageMode(MenuPageMode.LIST);
        MenuService menuService = org.mockito.Mockito.mock(MenuService.class);
        org.mockito.Mockito.when(menuService.currentUserVisibleMenu("menu-virtual")).thenReturn(menu);
        PlatformPageBootstrapService bootstrapService = new PlatformPageBootstrapService(
                menuService,
                snapshotService,
                moduleFieldService,
                fieldUiTypeService,
                fieldUiTypeAttributeService,
                fieldUiTypeFieldMappingService);

        PlatformPageBootstrap bootstrap = bootstrapService.bootstrapByMenu("menu-virtual", PlatformUiClientType.WEB);

        assertThat(bootstrap.resolvedConfig().uiFields()).hasSize(1);
        assertThat(bootstrap.resolvedConfig().uiFields().getFirst().fieldName()).isEqualTo("displayName");
        assertThat(bootstrap.resolvedConfig().uiFields().getFirst().fieldForm()).isEqualTo("VIRTUAL");
    }

    @Test
    void shouldRejectUiFieldFromAnotherModule() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String leadNameField = seedModuleField("crm.lead", "lead", "leadName", "lead_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "form", PlatformUiSetType.FORM, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));

        assertThatThrownBy(() -> uiConfigFieldService.insert(uiField(uiConfigId, leadNameField, "text")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same module");
    }

    @Test
    void shouldRejectMaximumDisplayLinesOutsideListUiSets() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "form", PlatformUiSetType.FORM, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        PlatformUiConfigField field = uiField(uiConfigId, customerNameField, "text");
        field.setMaxDisplayLines(2);

        assertThatThrownBy(() -> uiConfigFieldService.insert(field))
                .isInstanceOf(PlatformException.class)
                .hasMessage("UI config field maxDisplayLines is only supported by LIST UI sets");
    }

    @Test
    void shouldRejectUnsupportedUiTypeAndDuplicateDefaults() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedFieldType("decimal", FieldType.DECIMAL, DynamicQueryOperator.EQ);
        seedUiType("number", "decimal");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));

        assertThatThrownBy(() -> uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "number")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("mismatch");
        assertThatThrownBy(() -> uiSetService.insert(uiSet("crm.customer", "list2", PlatformUiSetType.LIST, true)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Only one default UI set");
    }

    @Test
    void shouldRejectMissingDefaultUiTypeAndRequiredWeakening() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField(
                "crm.customer", "customer", "customerName", "customer_name", "string", true);
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "form", PlatformUiSetType.FORM, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));

        assertThatThrownBy(() -> uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no default UI type");

        PlatformUiConfigField weakRequired = uiField(uiConfigId, customerNameField, "text");
        weakRequired.setRequiredOverride(false);
        assertThatThrownBy(() -> uiConfigFieldService.insert(weakRequired))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot weaken required");
    }

    @Test
    void shouldPublishAndUnpublishUiConfigThroughValidatedBoundary() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));

        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("at least one visible field");

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("{bad-json");
        uiConfigService.update(config);
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("layout JSON");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "querySummaries": []
                }
                """);
        uiConfigService.update(config);
        publishService.publishUiConfig(uiConfigId);

        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot("crm.customer");
        assertThat(snapshot.uiConfigs()).extracting(PlatformUiConfig::getId).containsExactly(uiConfigId);
        assertThat(snapshot.uiFields()).extracting(PlatformUiConfigField::getModuleMetadataFieldId)
                .containsExactly(customerNameField);

        publishService.unpublishUiConfig(uiConfigId);
        assertThat(snapshotService.snapshot("crm.customer").uiConfigs()).isEmpty();
        assertThat(snapshotService.snapshot("crm.customer").uiFields()).isEmpty();
    }

    @Test
    void shouldPreservePageNavigatorConfigurationAcrossPublishTransitions() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "project_list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","traits":[],"navigator":{"contextBindings":[{
                  "source":"NAVIGATOR","sourceKey":"project","target":"LIST_QUERY","targetKey":"projectId"
                }],"levels":[{
                  "key":"project","kind":"MICRO_LIST","sourceModuleAlias":"crm.project"
                }]}}""");
        uiConfigService.update(config);

        publishService.publishUiConfig(uiConfigId);

        assertThat(snapshotService.snapshot("crm.customer").uiConfigs()).singleElement()
                .satisfies(published -> assertThat(published.getLayoutJson()).contains("navigator", "projectId"));

        publishService.unpublishUiConfig(uiConfigId);

        PlatformUiConfig unpublished = uiConfigService.select(uiConfigId);
        assertThat(unpublished.getPublished()).isFalse();
        assertThat(unpublished.getLayoutJson()).contains("navigator", "projectId");
    }

    @Test
    void shouldRejectPageValuesThatTheRuntimeCannotCompile() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField(
                "crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("{\"template\":\"UNKNOWN\"}");
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("template is unsupported");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("{\"template\":\"LIST_DETAIL_CARD\",\"traits\":[\"UNKNOWN\"]}");
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("traits contains unsupported value");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","navigator":{"levels":[{
                  "key":"project","kind":"MICRO_LIST","sourceModuleAlias":"crm.project",
                  "singleResultPolicy":"UNKNOWN"
                }]}}
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("navigator layout is invalid")
                .hasRootCauseMessage("navigator level singleResultPolicy is unsupported: UNKNOWN");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","navigator":{"levels":[{
                  "key":"project","kind":"MICRO_LIST","sourceModuleAlias":"crm.project",
                  "management":true
                }]}}
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("navigator layout is invalid")
                .satisfies(error -> assertThat(error.getCause())
                        .hasMessageContaining("navigator management must be an object:"));
    }

    @Test
    void shouldRejectNavigatorManagementWhenSourceContractCannotBeProved() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField(
                "crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","navigator":{"levels":[{
                  "key":"project","kind":"MICRO_LIST","sourceModuleAlias":"crm.project",
                  "management":{"actions":["CREATE"]}
                }]}}
                """);
        uiConfigService.update(config);

        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService, moduleAlias -> Set.of(NavigatorSourceCapability.REFERENCE_QUERY));

        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Navigator source management contract is unavailable")
                .hasMessageContaining("source=crm.project");
    }

    @Test
    void shouldRejectPickerQueryWhosePublishedFormFieldIsHidden() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String titleField = seedModuleField("crm.directory", "directory", "title", "title", "string");
        String parentField = addModuleField("crm.directory", "parentId", "parent_id", "string");
        String formSetId = uiSetService.insert(uiSet("crm.directory", "directory_form", PlatformUiSetType.FORM, true));
        String formConfigId = uiConfigService.insert(uiConfig(formSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(formConfigId, titleField, "text"));
        PlatformUiConfigField hiddenParent = uiField(formConfigId, parentField, "text");
        hiddenParent.setVisible(Boolean.FALSE);
        uiConfigFieldService.insert(hiddenParent);
        publishService.publishUiConfig(formConfigId);

        String listSetId = uiSetService.insert(uiSet("crm.directory", "directory_list", PlatformUiSetType.LIST, true));
        String listConfigId = uiConfigService.insert(uiConfig(listSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(listConfigId, titleField, "text"));
        PlatformUiConfig listConfig = uiConfigService.select(listConfigId);
        listConfig.setLayoutJson("""
                {"template":"TREE_MANAGEMENT","navigator":{"contextBindings":[
                  {"source":"NAVIGATOR","sourceKey":"organization","target":"PICKER_QUERY",
                   "targetKey":"organizationId","targetPickerFieldKey":"parentId"}
                ],"levels":[
                  {"key":"organization","kind":"TREE","sourceModuleAlias":"iam.organization"}
                ]}}""");
        uiConfigService.update(listConfig);

        DynamicFieldDescriptor organizationId = org.mockito.Mockito.mock(DynamicFieldDescriptor.class);
        org.mockito.Mockito.when(organizationId.fieldName()).thenReturn("organizationId");
        org.mockito.Mockito.when(organizationId.reference()).thenReturn(new DynamicReferenceDescriptor(
                "directory", "organizationId", "iam.organization", "organization", ReferenceCardinality.ONE, List.of()));
        DynamicFieldDescriptor parentId = org.mockito.Mockito.mock(DynamicFieldDescriptor.class);
        org.mockito.Mockito.when(parentId.fieldName()).thenReturn("parentId");
        DynamicEntityDescriptor entity = new DynamicEntityDescriptor("directory", "目录", Set.of(),
                List.of(organizationId, parentId), List.of(), List.of(), List.of(), List.of());
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        org.mockito.Mockito.when(recordService.describe("crm.directory")).thenReturn(new DynamicModuleDescriptor(
                "crm.directory", "目录", "directory", List.of(), List.of(entity), List.of(), List.of(), List.of()));
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService);

        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(listConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Picker query target must be declared by a published form editor")
                .hasMessageContaining("crm.directory.parentId");
    }

    @Test
    void shouldRejectHigherPriorityFormThatWouldInvalidatePublishedPickerQuery() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String titleField = seedModuleField("crm.directory", "directory", "title", "title", "string");
        String parentField = addModuleField("crm.directory", "parentId", "parent_id", "string");
        String primaryFormSetId = uiSetService.insert(uiSet("crm.directory", "directory_form", PlatformUiSetType.FORM, true));
        PlatformUiConfig primaryForm = uiConfig(primaryFormSetId, PlatformUiClientType.WEB, false);
        primaryForm.setSortOrder(100);
        String primaryFormConfigId = uiConfigService.insert(primaryForm);
        uiConfigFieldService.insert(uiField(primaryFormConfigId, titleField, "text"));
        uiConfigFieldService.insert(uiField(primaryFormConfigId, parentField, "text"));
        publishService.publishUiConfig(primaryFormConfigId);

        String listSetId = uiSetService.insert(uiSet("crm.directory", "directory_list", PlatformUiSetType.LIST, true));
        String listConfigId = uiConfigService.insert(uiConfig(listSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(listConfigId, titleField, "text"));
        PlatformUiConfig listConfig = uiConfigService.select(listConfigId);
        listConfig.setLayoutJson("""
                {"template":"TREE_MANAGEMENT","navigator":{"contextBindings":[
                  {"source":"NAVIGATOR","sourceKey":"organization","target":"PICKER_QUERY",
                   "targetKey":"organizationId","targetPickerFieldKey":"parentId"}
                ],"levels":[
                  {"key":"organization","kind":"TREE","sourceModuleAlias":"iam.organization"}
                ]}}""");
        uiConfigService.update(listConfig);

        DynamicFieldDescriptor organizationId = org.mockito.Mockito.mock(DynamicFieldDescriptor.class);
        org.mockito.Mockito.when(organizationId.fieldName()).thenReturn("organizationId");
        org.mockito.Mockito.when(organizationId.reference()).thenReturn(new DynamicReferenceDescriptor(
                "directory", "organizationId", "iam.organization", "organization", ReferenceCardinality.ONE, List.of()));
        DynamicFieldDescriptor parentId = org.mockito.Mockito.mock(DynamicFieldDescriptor.class);
        org.mockito.Mockito.when(parentId.fieldName()).thenReturn("parentId");
        DynamicEntityDescriptor entity = new DynamicEntityDescriptor("directory", "目录", Set.of("TREE"),
                List.of(organizationId, parentId), List.of(), List.of(), List.of(), List.of());
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        org.mockito.Mockito.when(recordService.describe("crm.directory")).thenReturn(new DynamicModuleDescriptor(
                "crm.directory", "目录", "directory", List.of(), List.of(entity), List.of(), List.of(), List.of()));
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService);
        verifyingPublishService.publishUiConfig(listConfigId);

        String replacementFormSetId = uiSetService.insert(uiSet("crm.directory", "directory_form_replacement",
                PlatformUiSetType.FORM, false));
        PlatformUiConfig replacementForm = uiConfig(replacementFormSetId, PlatformUiClientType.WEB, false);
        replacementForm.setSortOrder(0);
        String replacementFormConfigId = uiConfigService.insert(replacementForm);
        uiConfigFieldService.insert(uiField(replacementFormConfigId, titleField, "text"));

        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(replacementFormConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Picker query target must be declared by a published form editor")
                .hasMessageContaining("crm.directory.parentId");
    }

    @Test
    void shouldRejectPublishedNavigatorLevelsWhoseSourceDoesNotExposeReferenceProjection() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "customer_list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {"navigator":{"levels":[{
                  "key":"customer","kind":"MICRO_LIST","sourceModuleAlias":"crm.customer"
                }]}}""");
        uiConfigService.update(config);
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService, moduleAlias -> java.util.Set.of());

        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("page=crm.customer")
                .hasMessageContaining("uiConfig=" + uiConfigId)
                .hasMessageContaining("level=customer")
                .hasMessageContaining("source=crm.customer")
                .hasMessageContaining("required=REFERENCE_QUERY");
    }

    @Test
    void shouldRejectUnsupportedPageContextBindingsBeforePublishingDynamicPageConfig() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "customer_list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));

        for (String binding : List.of(
                "{\"source\":\"ROUTE\",\"sourceKey\":\"tenantId\",\"target\":\"LIST_QUERY\",\"targetKey\":\"tenantId\"}",
                "{\"source\":\"FORM_FIELD\",\"sourceKey\":\"tenantId\",\"target\":\"FORM_DEFAULT\",\"targetKey\":\"tenantId\"}",
                "{\"source\":\"SESSION\",\"sourceKey\":\"tenantId\",\"target\":\"MUTATION_CONSTRAINT\",\"targetKey\":\"tenantId\"}")) {
            PlatformUiConfig config = uiConfigService.select(uiConfigId);
            config.setLayoutJson("{\"navigator\":{\"contextBindings\":[" + binding + "],\"levels\":[]}}");
            uiConfigService.update(config);

            assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("navigator layout is invalid");
        }
    }

    @Test
    void shouldRejectSemanticallyInvalidLayoutJsonBeforePublish() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "querySummaries": {"key":"matched"}
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("querySummaries must be array");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "children": [
                    {"title":"明细"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("children[0].relationCode is required");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"associationView", "key":"contracts"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].viewCode is required");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "key":"baseInfo"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].actionCode is required");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo", "width":0}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].width must be positive integer");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo", "refresh":{"list":"no"}}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].refresh.list must be boolean");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"dialog", "key":"submitDialog"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].actionCode is required");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "referenceCandidates": [
                    {"sourceUiConfigId":"ui-form", "uiConfigId":"ui-ref", "queryTemplateId":"q-ref"}
                  ],
                  "blocks": [
                    {"type":"associationView", "key":"contracts", "viewCode":"contracts"},
                    {"type":"dialog", "key":"submitDialog", "actionCode":"submitDialog", "position":"recordToolbar", "width":640},
                    {"type":"taskPanel", "key":"completion"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatCode(() -> publishService.publishUiConfig(uiConfigId)).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateAssociationViewCodeAgainstDynamicDescriptorWhenAvailable() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "detail", PlatformUiSetType.DETAIL, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        org.mockito.Mockito.when(recordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer",
                "Customer",
                "customer",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DynamicAssociationViewDescriptor("contracts", "customer", "crm.contract", "contract",
                        AssociationViewDisplayMode.INLINE_LIST, "contracts", null, EntityViewType.LIST, true))
        ));
        org.mockito.Mockito.when(recordService.describe("crm.contract")).thenReturn(new DynamicModuleDescriptor(
                "crm.contract",
                "Contract",
                "contract",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        org.mockito.Mockito.when(recordService.describe("crm.missing"))
                .thenThrow(new PlatformException("unknown module"));
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService);
        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"associationView", "viewCode":"missing"}
                  ]
                }
                """);
        uiConfigService.update(config);

        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("viewCode is unknown");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"associationView", "viewCode":"contracts"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatCode(() -> verifyingPublishService.publishUiConfig(uiConfigId)).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateTaskPanelCheckConfiguration() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "detail", PlatformUiSetType.DETAIL, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "ready", false));
        publishService.publishQueryTemplate(templateId);
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        org.mockito.Mockito.when(recordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer",
                "Customer",
                "customer",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DynamicAssociationViewDescriptor("contracts", "customer", "crm.contract", "contract",
                        AssociationViewDisplayMode.INLINE_LIST, "contracts", null, EntityViewType.LIST, true))
        ));
        org.mockito.Mockito.when(recordService.describe("crm.contract")).thenReturn(new DynamicModuleDescriptor(
                "crm.contract",
                "Contract",
                "contract",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        org.mockito.Mockito.when(recordService.describe("crm.missing"))
                .thenThrow(new PlatformException("unknown module"));
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService);

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"taskPanel", "checkType":"QUERY_TEMPLATE", "queryTemplateId":"%s"}
                  ]
                }
                """.formatted(templateId));
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("blocks[0].key is required");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"taskPanel", "key":"ready", "checkType":"ASSOCIATION_VIEW", "associationViewCode":"missing"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("associationViewCode.viewCode is unknown");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {
                      "type":"taskPanel",
                      "key":"generated",
                      "checkType":"GENERATED_RELATION",
                      "targetModuleAlias":"crm.missing"
                    }
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetModuleAlias is unknown");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {
                      "type":"taskPanel",
                      "key":"ready",
                      "checkType":"QUERY_TEMPLATE",
                      "queryTemplateId":"%s",
                      "externalRecordIdKey":"recordId",
                      "diagnosticPath":"/crm.customer/query"
                    },
                    {"type":"taskPanel", "key":"contracts", "checkType":"ASSOCIATION_VIEW", "associationViewCode":"contracts"},
                    {"type":"taskPanel", "key":"generated", "checkType":"GENERATED_RELATION", "targetModuleAlias":"crm.contract"}
                  ]
                }
                """.formatted(templateId));
        uiConfigService.update(config);
        assertThatCode(() -> verifyingPublishService.publishUiConfig(uiConfigId)).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateLocalEditTargetConfigBeforePublishingSourceConfig() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String sourceSetId = uiSetService.insert(uiSet("crm.customer", "detail", PlatformUiSetType.DETAIL, true));
        String sourceConfigId = uiConfigService.insert(uiConfig(sourceSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(sourceConfigId, customerNameField, "text"));
        String targetSetId = uiSetService.insert(uiSet("crm.customer", "localedit", PlatformUiSetType.FORM, false));
        String targetConfigId = uiConfigService.insert(uiConfig(targetSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(targetConfigId, customerNameField, "text"));

        PlatformUiConfig targetConfig = uiConfigService.select(targetConfigId);
        targetConfig.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo", "targetUiConfigId":"%s"}
                  ]
                }
                """.formatted(targetConfigId));
        uiConfigService.update(targetConfig);
        PlatformUiConfig sourceConfig = uiConfigService.select(sourceConfigId);
        sourceConfig.setLayoutJson("""
                {"blocks":[{"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo"}]}
                """);
        uiConfigService.update(sourceConfig);
        assertThatThrownBy(() -> publishService.publishUiConfig(sourceConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetUiConfigId is required");

        sourceConfig = uiConfigService.select(sourceConfigId);
        sourceConfig.setLayoutJson("""
                {"blocks":[{"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo", "targetUiConfigId":"%s"}]}
                """.formatted(sourceConfigId));
        uiConfigService.update(sourceConfig);
        assertThatThrownBy(() -> publishService.publishUiConfig(sourceConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetUiConfigId must use FORM UI set");

        sourceConfig = uiConfigService.select(sourceConfigId);
        sourceConfig.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "key":"baseInfo", "actionCode":"editBaseInfo", "targetUiConfigId":"%s"}
                  ]
                }
                """.formatted(targetConfigId));
        uiConfigService.update(sourceConfig);

        assertThatThrownBy(() -> publishService.publishUiConfig(sourceConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetUiConfigId must be published and enabled");

        publishService.publishUiConfig(targetConfigId);

        PlatformUiSet targetSet = uiSetService.select(targetSetId);
        targetSet.setEnabled(false);
        uiSetService.update(targetSet);
        assertThatThrownBy(() -> publishService.publishUiConfig(sourceConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetUiConfigId must use enabled UI set");
        targetSet = uiSetService.select(targetSetId);
        targetSet.setEnabled(true);
        uiSetService.update(targetSet);

        assertThatCode(() -> publishService.publishUiConfig(sourceConfigId)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectLocalEditControlsThatNeedUnpublishedOptionOrReferenceDescriptors() {
        seedFieldType("json_set", FieldType.JSON, DynamicQueryOperator.CONTAINS_ANY);
        seedUiType("multi_select", "json_set");
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
            FieldUiControl multiSelect = fieldUiTypeService.requireFieldUiControl("multi_select");
            multiSelect.setRendererType(ViewControlType.MULTI_SELECT);
            multiSelect.setValueShape(FieldUiControlValueShape.COLLECTION);
            fieldUiTypeService.update(multiSelect);
            String tagsField = seedModuleField("crm.customer", "customer", "tags", "tags", "json_set");
            String formSetId = uiSetService.insert(uiSet("crm.customer", "local_edit", PlatformUiSetType.FORM, false));
            String formConfigId = uiConfigService.insert(uiConfig(formSetId, PlatformUiClientType.WEB, false));
            uiConfigFieldService.insert(uiField(formConfigId, tagsField, "multi_select"));
            PlatformUiConfig formConfig = uiConfigService.select(formConfigId);
            formConfig.setLayoutJson("""
                    {"blocks":[{"type":"localEdit","actionCode":"editTags","targetUiConfigId":"%s"}]}
                    """.formatted(formConfigId));
            uiConfigService.update(formConfig);

            assertThatThrownBy(() -> publishService.publishUiConfig(formConfigId))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("Local edit field control requires an option/reference descriptor");
        } finally {
            PlatformAbilityRuntime.resetChildAbilityResolver();
        }
    }

    @Test
    void shouldValidateActionBlocksAgainstDynamicDescriptorWhenAvailable() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "detail", PlatformUiSetType.DETAIL, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        DynamicRecordService recordService = org.mockito.Mockito.mock(DynamicRecordService.class);
        org.mockito.Mockito.when(recordService.action("crm.customer", "submitDialog")).thenReturn(action(
                "submitDialog", EntityActionExecutorType.DIALOG, "submitDialog#submit"));
        org.mockito.Mockito.when(recordService.action("crm.customer", "submit")).thenReturn(action(
                "submit", EntityActionExecutorType.SERVICE, "contractSubmit"));
        org.mockito.Mockito.when(recordService.action("crm.customer", "editBaseInfo")).thenReturn(action(
                "editBaseInfo", EntityActionExecutorType.SERVICE, DynamicLocalEditActionExecutor.EXECUTOR_KEY));
        PlatformPageConfigPublishService verifyingPublishService = new PlatformPageConfigPublishService(
                uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService,
                recordService);
        String localEditSetId = uiSetService.insert(uiSet("crm.customer", "local_edit", PlatformUiSetType.FORM, false));
        String localEditConfigId = uiConfigService.insert(uiConfig(localEditSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(localEditConfigId, customerNameField, "text"));
        PlatformUiConfig localEditConfig = uiConfigService.select(localEditConfigId);
        localEditConfig.setLayoutJson("""
                {"blocks":[{"type":"localEdit","actionCode":"editBaseInfo","targetUiConfigId":"%s"}]}
                """.formatted(localEditConfigId));
        uiConfigService.update(localEditConfig);
        verifyingPublishService.publishUiConfig(localEditConfigId);

        PlatformUiConfig config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"dialog", "actionCode":"submit"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must be DIALOG action");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"action", "actionCode":"submitDialog"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must not be DIALOG action");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"localEdit", "actionCode":"submit"}
                  ]
                }
                """);
        uiConfigService.update(config);
        assertThatThrownBy(() -> verifyingPublishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must use local edit executor");

        config = uiConfigService.select(uiConfigId);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type":"dialog", "actionCode":"submitDialog"},
                    {"type":"localEdit", "actionCode":"editBaseInfo", "targetUiConfigId":"%s"}
                  ]
                }
                """.formatted(localEditConfigId));
        uiConfigService.update(config);
        assertThatCode(() -> verifyingPublishService.publishUiConfig(uiConfigId)).doesNotThrowAnyException();
    }

    private DynamicActionDescriptor action(String code,
                                           EntityActionExecutorType executorType,
                                           String executorKey) {
        return new DynamicActionDescriptor(code, code, true, EntityActionLevel.RECORD,
                EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED, true, true,
                null, false, null, executorType, executorKey);
    }

    @Test
    void shouldScaffoldDefaultClientConfigsForUiSet() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String levelField = addModuleField("crm.customer", "level", "level", "string");
        String childField = addChildModuleField("crm.customer", "contact", "contactName", "contact_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "form", PlatformUiSetType.FORM, true));

        List<String> configIds = scaffoldService.scaffoldDefaultClientConfigs(uiSetId);

        assertThat(configIds).hasSize(2);
        assertThat(uiConfigService.listByUiSetIds(List.of(uiSetId)))
                .extracting(PlatformUiConfig::getClientType)
                .containsExactly(PlatformUiClientType.WEB, PlatformUiClientType.APP);
        assertThat(uiConfigFieldService.listByUiConfigIds(configIds))
                .extracting(PlatformUiConfigField::getModuleMetadataFieldId)
                .contains(customerNameField, levelField);
        assertThat(uiConfigFieldService.listByUiConfigIds(configIds))
                .extracting(PlatformUiConfigField::getModuleMetadataFieldId)
                .doesNotContain(childField);
        assertThat(uiConfigFieldService.listByUiConfigIds(configIds))
                .extracting(PlatformUiConfigField::getFieldUiControlAlias)
                .containsOnly("text");

        uiConfigService.disable(configIds.getFirst());
        List<String> secondRun = scaffoldService.scaffoldDefaultClientConfigs(uiSetId);
        assertThat(secondRun).containsExactlyElementsOf(configIds);
        assertThat(uiConfigDao.query(Criteria.of().eq("uiSetId", uiSetId), new PageRequest(0, 10))).hasSize(2);
    }

    @Test
    void shouldCreateQueryTemplateAndCompileCriteria() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        String groupId = queryItemService.insert(queryGroup(templateId, TreeAbility.ROOT_ID, PlatformQueryGroupOperator.AND));
        PlatformQueryItem item = queryLeaf(templateId, groupId, customerNameField, DynamicQueryOperator.LIKE);
        item.setAllowExternalValue(true);
        item.setExternalValueKey("keyword");
        queryItemService.insert(item);

        Criteria criteria = queryItemService.compile(templateId, Map.of("keyword", "acme"));

        List<CriteriaClause> clauses = clauses(criteria);
        assertThat(clauses).hasSize(1);
        assertThat(clauses.getFirst().getField()).isEqualTo("customerName");
        assertThat(clauses.getFirst().getOperator()).isEqualTo(CriteriaOperator.LIKE);
        assertThat(clauses.getFirst().getValues()).containsExactly("acme");
    }

    @Test
    void shouldCompileQueryTemplateDateRangeByQueryItemTimeZone() {
        seedFieldType("zoned_datetime", FieldType.ZONED_TIMESTAMP, DynamicQueryOperator.BETWEEN);
        seedUiType("date_time_with_time_zone", "zoned_datetime");
        String meetingAtField = seedModuleField("crm.meeting", "meeting", "meetingAt", "meeting_at",
                "zoned_datetime");
        String templateId = queryTemplateService.insert(queryTemplate("crm.meeting", "date_range", true));
        PlatformQueryItem item = queryLeaf(templateId, TreeAbility.ROOT_ID, meetingAtField,
                DynamicQueryOperator.BETWEEN);
        item.setDefaultValue("2026-01-01,2026-01-31");
        item.setTimeZone("Asia/Shanghai");
        queryItemService.insert(item);

        Criteria criteria = queryItemService.compile(templateId);

        List<CriteriaClause> clauses = clauses(criteria);
        assertThat(clauses).hasSize(2);
        assertThat(clauses.get(0).getField()).isEqualTo("meetingAt");
        assertThat(clauses.get(0).getOperator()).isEqualTo(CriteriaOperator.GTE);
        assertThat(clauses.get(0).getValues()).containsExactly(Instant.parse("2025-12-31T16:00:00Z"));
        assertThat(clauses.get(1).getField()).isEqualTo("meetingAt");
        assertThat(clauses.get(1).getOperator()).isEqualTo(CriteriaOperator.LT);
        assertThat(clauses.get(1).getValues()).containsExactly(Instant.parse("2026-01-31T16:00:00Z"));
    }

    @Test
    void shouldCompileQueryTemplateDateRangeByInjectedDefaultTimeZone() {
        PlatformQueryItemService shanghaiQueryItemService = new PlatformQueryItemService(
                queryItemDao,
                queryTemplateService,
                moduleFieldService,
                fieldTypeService,
                new net.ximatai.muyun.spring.common.time.PlatformTimeService(
                        Clock.systemUTC(),
                        ZoneId.of("Asia/Shanghai"),
                        List.of()
                )
        );
        seedFieldType("timestamp", FieldType.TIMESTAMP, DynamicQueryOperator.BETWEEN);
        seedUiType("datetime", "timestamp");
        String submittedAtField = seedModuleField("crm.case", "case", "submittedAt", "submitted_at",
                "timestamp");
        String templateId = queryTemplateService.insert(queryTemplate("crm.case", "date_range", true));
        PlatformQueryItem item = queryLeaf(templateId, TreeAbility.ROOT_ID, submittedAtField,
                DynamicQueryOperator.BETWEEN);
        item.setDefaultValue("2026-01-01,2026-01-01");
        shanghaiQueryItemService.insert(item);

        Criteria criteria = shanghaiQueryItemService.compile(templateId);

        List<CriteriaClause> clauses = clauses(criteria);
        assertThat(clauses).hasSize(2);
        assertThat(clauses.get(0).getValues()).containsExactly(Instant.parse("2025-12-31T16:00:00Z"));
        assertThat(clauses.get(1).getValues()).containsExactly(Instant.parse("2026-01-01T16:00:00Z"));
    }

    @Test
    void shouldRejectQueryTemplateReversedDateRangeWithPlatformException() {
        seedFieldType("timestamp", FieldType.TIMESTAMP, DynamicQueryOperator.BETWEEN);
        seedUiType("datetime", "timestamp");
        String submittedAtField = seedModuleField("crm.ticket", "ticket", "submittedAt", "submitted_at",
                "timestamp");
        String templateId = queryTemplateService.insert(queryTemplate("crm.ticket", "date_range", true));
        PlatformQueryItem item = queryLeaf(templateId, TreeAbility.ROOT_ID, submittedAtField,
                DynamicQueryOperator.BETWEEN);
        item.setDefaultValue("2026-01-02,2026-01-01");
        item.setTimeZone("Asia/Shanghai");
        queryItemService.insert(item);

        assertThatThrownBy(() -> queryItemService.compile(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("date range");
    }

    @Test
    void shouldRejectQueryTemplateInvalidTimeZoneWithPlatformException() {
        seedFieldType("timestamp", FieldType.TIMESTAMP, DynamicQueryOperator.BETWEEN);
        seedUiType("datetime", "timestamp");
        String submittedAtField = seedModuleField("crm.ticket", "ticket", "submittedAt", "submitted_at",
                "timestamp");
        String templateId = queryTemplateService.insert(queryTemplate("crm.ticket", "date_range", true));
        PlatformQueryItem item = queryLeaf(templateId, TreeAbility.ROOT_ID, submittedAtField,
                DynamicQueryOperator.BETWEEN);
        item.setDefaultValue("2026-01-01,2026-01-02");
        item.setTimeZone("+08:00");
        queryItemService.insert(item);

        assertThatThrownBy(() -> queryItemService.compile(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("timeZone");
    }

    @Test
    void shouldPublishPageConfigAndKeepDraftsOutOfOnlineSnapshot() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String draftUiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(draftUiConfigId, customerNameField, "text"));
        String draftTemplateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        queryItemService.insert(queryLeaf(draftTemplateId, TreeAbility.ROOT_ID, customerNameField,
                DynamicQueryOperator.LIKE));

        PlatformPageConfigSnapshot draftSnapshot = snapshotService.snapshot("crm.customer");
        assertThat(draftSnapshot.uiConfigs()).isEmpty();
        assertThat(draftSnapshot.queryTemplates()).isEmpty();

        publishService.publishUiConfig(draftUiConfigId);
        publishService.publishQueryTemplate(draftTemplateId);
        PlatformPageConfigSnapshot onlineSnapshot = snapshotService.snapshot("crm.customer");

        assertThat(onlineSnapshot.uiConfigs()).extracting(PlatformUiConfig::getId).containsExactly(draftUiConfigId);
        assertThat(onlineSnapshot.queryTemplates()).extracting(PlatformQueryTemplate::getId)
                .containsExactly(draftTemplateId);
        assertThat(onlineSnapshot.queryItems()).extracting(PlatformQueryItem::getQueryTemplateId)
                .containsExactly(draftTemplateId);
    }

    @Test
    void shouldRequireUnpublishBeforeEditingPublishedPageConfig() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        String fieldId = uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        publishService.publishUiConfig(uiConfigId);

        PlatformUiConfig publishedConfig = uiConfigUpdate(uiConfigService.select(uiConfigId));
        publishedConfig.setLayoutJson("{\"changed\":true}");
        PlatformUiConfig editedPublishedConfig = publishedConfig;
        assertThatThrownBy(() -> uiConfigService.update(editedPublishedConfig))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.ui-config.published-edit-denied"))
                .hasMessageContaining("Published UI config cannot be edited");

        PlatformUiConfigField publishedField = uiFieldUpdate(uiConfigFieldService.select(fieldId));
        publishedField.setVisible(false);
        assertThatThrownBy(() -> uiConfigFieldService.update(publishedField))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Published UI config fields cannot be edited");
        assertThatThrownBy(() -> uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Published UI config fields cannot be edited");

        publishService.unpublishUiConfig(uiConfigId);
        publishedConfig = uiConfigUpdate(uiConfigService.select(uiConfigId));
        publishedConfig.setLayoutJson("{\"changed\":true}");
        PlatformUiConfig editedDraftConfig = publishedConfig;
        assertThatCode(() -> uiConfigService.update(editedDraftConfig)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDirectUiConfigPublishOutsidePublishService() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));

        assertThatThrownBy(() -> uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, true)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.ui-config.direct-publish-denied"))
                .hasMessageContaining("only be published through publish service");

        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        PlatformUiConfig directPublish = uiConfigUpdate(uiConfigService.select(uiConfigId));
        directPublish.setPublished(true);
        assertThatThrownBy(() -> uiConfigService.update(directPublish))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.ui-config.direct-publish-denied"))
                .hasMessageContaining("only be published through publish service");

        assertThatCode(() -> publishService.publishUiConfig(uiConfigId)).doesNotThrowAnyException();
    }

    @Test
    void shouldRequireUnpublishBeforeEditingPublishedQueryTemplate() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        String itemId = queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField,
                DynamicQueryOperator.LIKE));
        publishService.publishQueryTemplate(templateId);

        PlatformQueryTemplate publishedTemplate = queryTemplateUpdate(queryTemplateService.select(templateId));
        publishedTemplate.setTitle("Changed");
        PlatformQueryTemplate editedPublishedTemplate = publishedTemplate;
        assertThatThrownBy(() -> queryTemplateService.update(editedPublishedTemplate))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.query-template.published-edit-denied"))
                .hasMessageContaining("Published query template cannot be edited");

        PlatformQueryItem publishedItem = queryItemUpdate(queryItemService.select(itemId));
        publishedItem.setDefaultValue("changed");
        assertThatThrownBy(() -> queryItemService.update(publishedItem))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Published query template items cannot be edited");
        assertThatThrownBy(() -> queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID,
                customerNameField, DynamicQueryOperator.LIKE)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Published query template items cannot be edited");

        publishService.unpublishQueryTemplate(templateId);
        publishedTemplate = queryTemplateUpdate(queryTemplateService.select(templateId));
        publishedTemplate.setTitle("Changed");
        PlatformQueryTemplate editedDraftTemplate = publishedTemplate;
        assertThatCode(() -> queryTemplateService.update(editedDraftTemplate)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDirectQueryTemplatePublishOutsidePublishService() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");

        PlatformQueryTemplate directInsert = queryTemplate("crm.customer", "direct", true);
        directInsert.setPublished(true);
        assertThatThrownBy(() -> queryTemplateService.insert(directInsert))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.query-template.direct-publish-denied"))
                .hasMessageContaining("only be published through publish service");

        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField,
                DynamicQueryOperator.LIKE));
        PlatformQueryTemplate directPublish = queryTemplateUpdate(queryTemplateService.select(templateId));
        directPublish.setPublished(true);
        assertThatThrownBy(() -> queryTemplateService.update(directPublish))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.query-template.direct-publish-denied"))
                .hasMessageContaining("only be published through publish service");

        assertThatCode(() -> publishService.publishQueryTemplate(templateId)).doesNotThrowAnyException();
    }

    @Test
    void shouldReturnBusinessCodesForUiSetConfigurationFailures() {
        PlatformUiSet missingModule = uiSet("crm.missing", "list", PlatformUiSetType.LIST, false);
        assertThatThrownBy(() -> uiSetService.insert(missingModule))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code()).isEqualTo("platform.ui-set.module-not-found"))
                .hasMessageContaining("requires existing module");

        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        PlatformUiSet missingType = uiSet("crm.customer", "list", null, false);
        assertThatThrownBy(() -> uiSetService.insert(missingType))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code()).isEqualTo("platform.ui-set.type-required"))
                .hasMessageContaining("type must not be null");
    }

    @Test
    void shouldReturnBusinessCodesForQueryTemplateConfigurationFailures() {
        PlatformQueryTemplate missingModule = queryTemplate("crm.missing", "default", false);
        assertThatThrownBy(() -> queryTemplateService.insert(missingModule))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.query-template.module-not-found"))
                .hasMessageContaining("requires existing module");
    }

    @Test
    void shouldRejectBrokenConfigBeforePublish() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedFieldType("decimal", FieldType.DECIMAL, DynamicQueryOperator.EQ);
        seedUiType("text", "string");
        seedUiType("number", "decimal");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String uiSetId = uiSetService.insert(uiSet("crm.customer", "list", PlatformUiSetType.LIST, true));
        String uiConfigId = uiConfigService.insert(uiConfig(uiSetId, PlatformUiClientType.WEB, false));
        String uiFieldId = uiConfigFieldService.insert(uiField(uiConfigId, customerNameField, "text"));
        PlatformUiConfigField brokenUiField = uiConfigFieldService.select(uiFieldId);
        brokenUiField.setFieldUiControlAlias("number");
        uiConfigFieldDao.updateById(brokenUiField);

        assertThatThrownBy(() -> publishService.publishUiConfig(uiConfigId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("mismatch");

        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        String itemId = queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField,
                DynamicQueryOperator.LIKE));
        PlatformQueryItem detached = queryItemService.select(itemId);
        detached.setParentId("missing-parent");
        queryItemDao.updateById(detached);

        assertThatThrownBy(() -> publishService.publishQueryTemplate(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("outside root tree");
    }

    @Test
    void shouldValidateQueryTemplateBeforePublishingBoundary() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        queryItemService.insert(queryGroup(templateId, TreeAbility.ROOT_ID, PlatformQueryGroupOperator.AND));

        assertThatCode(() -> publishService.validateQueryTemplatePublishable(templateId))
                .doesNotThrowAnyException();

        String detachedId = queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField,
                DynamicQueryOperator.LIKE));
        PlatformQueryItem detached = queryItemService.select(detachedId);
        detached.setParentId("missing-parent");
        queryItemDao.updateById(detached);

        assertThatThrownBy(() -> publishService.validateQueryTemplatePublishable(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("outside root tree");
    }

    @Test
    void shouldCompileGroupedQueryOperatorsAndSkipDisabledItems() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE, DynamicQueryOperator.IN,
                DynamicQueryOperator.NOT_IN, DynamicQueryOperator.NULL);
        seedFieldType("integer", FieldType.INTEGER, DynamicQueryOperator.EQ, DynamicQueryOperator.BETWEEN);
        seedUiType("text", "string");
        seedUiType("number", "integer");
        String nameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String levelField = addModuleField("crm.customer", "level", "level", "integer");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "advanced", true));
        String groupId = queryItemService.insert(queryGroup(templateId, TreeAbility.ROOT_ID, PlatformQueryGroupOperator.OR));
        PlatformQueryItem nameItem = queryLeaf(templateId, groupId, nameField, DynamicQueryOperator.IN);
        nameItem.setDefaultValue("alice,bob");
        queryItemService.insert(nameItem);
        PlatformQueryItem excludedNameItem = queryLeaf(templateId, groupId, nameField, DynamicQueryOperator.NOT_IN);
        excludedNameItem.setDefaultValue("mallory,eve");
        queryItemService.insert(excludedNameItem);
        PlatformQueryItem emptyNameItem = queryLeaf(templateId, groupId, nameField, DynamicQueryOperator.NULL);
        queryItemService.insert(emptyNameItem);
        PlatformQueryItem levelItem = queryLeaf(templateId, groupId, levelField, DynamicQueryOperator.BETWEEN);
        levelItem.setDefaultValue("1,10");
        queryItemService.insert(levelItem);
        String disabledId = queryItemService.insert(queryLeaf(templateId, groupId, nameField, DynamicQueryOperator.LIKE));
        queryItemService.disable(disabledId);

        Criteria criteria = queryItemService.compile(templateId);

        List<CriteriaClause> clauses = clauses(criteria);
        assertThat(clauses).extracting(CriteriaClause::getOperator)
                .containsExactlyInAnyOrder(CriteriaOperator.IN, CriteriaOperator.NOT_IN,
                        CriteriaOperator.IS_NULL, CriteriaOperator.BETWEEN);
    }

    @Test
    void shouldCompileCollectionQueryOperatorsForJsonTemplateItems() {
        seedFieldType("json_set", FieldType.JSON, DynamicQueryOperator.EQ,
                DynamicQueryOperator.CONTAINS, DynamicQueryOperator.CONTAINS_ANY,
                DynamicQueryOperator.CONTAINS_ALL, DynamicQueryOperator.EMPTY,
                DynamicQueryOperator.NOT_EMPTY);
        seedUiType("multi_select", "json_set");
        String tagsField = seedModuleField("crm.customer", "customer", "tags", "tags", "json_set");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "json_query", true));
        String groupId = queryItemService.insert(queryGroup(templateId, TreeAbility.ROOT_ID, PlatformQueryGroupOperator.AND));
        PlatformQueryItem contains = queryLeaf(templateId, groupId, tagsField, DynamicQueryOperator.CONTAINS);
        contains.setDefaultValue("vip");
        queryItemService.insert(contains);
        PlatformQueryItem any = queryLeaf(templateId, groupId, tagsField, DynamicQueryOperator.CONTAINS_ANY);
        any.setDefaultValue("vip,trial");
        queryItemService.insert(any);
        PlatformQueryItem all = queryLeaf(templateId, groupId, tagsField, DynamicQueryOperator.CONTAINS_ALL);
        all.setDefaultValue("vip,paid");
        queryItemService.insert(all);
        PlatformQueryItem empty = queryLeaf(templateId, groupId, tagsField, DynamicQueryOperator.EMPTY);
        empty.setDefaultValue(null);
        queryItemService.insert(empty);
        PlatformQueryItem notEmpty = queryLeaf(templateId, groupId, tagsField, DynamicQueryOperator.NOT_EMPTY);
        notEmpty.setDefaultValue(null);
        queryItemService.insert(notEmpty);

        Criteria criteria = queryItemService.compile(templateId);

        assertThat(clauses(criteria)).extracting(CriteriaClause::getOperator)
                .containsExactly(CriteriaOperator.CONTAINS, CriteriaOperator.CONTAINS_ANY,
                        CriteriaOperator.CONTAINS_ALL, CriteriaOperator.IS_EMPTY,
                        CriteriaOperator.IS_NOT_EMPTY);
    }

    @Test
    void shouldRejectQueryItemCyclesAndDetachedTrees() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        String parentId = queryItemService.insert(queryGroup(templateId, TreeAbility.ROOT_ID, PlatformQueryGroupOperator.AND));
        String childId = queryItemService.insert(queryGroup(templateId, parentId, PlatformQueryGroupOperator.AND));
        queryItemService.insert(queryLeaf(templateId, childId, customerNameField, DynamicQueryOperator.LIKE));

        PlatformQueryItem parent = queryItemService.select(parentId);
        parent.setParentId(childId);
        assertThatThrownBy(() -> queryItemService.update(parent))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("descendant");

        PlatformQueryItem detached = queryItemService.select(childId);
        detached.setParentId("missing-parent");
        queryItemDao.updateById(detached);
        assertThatThrownBy(() -> queryItemService.compile(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("outside root tree");
    }

    @Test
    void shouldRejectUnsupportedQueryOperator() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));

        assertThatThrownBy(() -> queryItemService.insert(
                queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, DynamicQueryOperator.BETWEEN)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("operator is not allowed");
    }

    @Test
    void shouldRejectQueryItemWhenFieldConfigDisablesQuery() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        ResolvedField resolved = resolvedField(customerNameField);
        MetadataFieldConfig config = fieldConfig(resolved.metadataFieldId());
        config.setQueryable(false);
        fieldConfigService.insert(config);
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));

        assertThatThrownBy(() -> queryItemService.insert(
                queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, DynamicQueryOperator.LIKE)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not queryable");
    }

    @Test
    void shouldUseRelationScopedFieldQueryConfigForQueryItem() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE, DynamicQueryOperator.EQ);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        ResolvedField resolved = resolvedField(customerNameField);
        MetadataFieldConfig config = fieldConfig(resolved.metadataFieldId());
        config.setRelationId(resolved.relationId());
        config.setQueryable(true);
        config.setDefaultQueryOperator(DynamicQueryOperator.EQ);
        config.setQueryOperators(Set.of(DynamicQueryOperator.EQ.name()));
        fieldConfigService.insert(config);
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));

        String itemId = queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, null));

        assertThat(queryItemService.select(itemId).getOperator()).isEqualTo(DynamicQueryOperator.EQ);
        assertThatThrownBy(() -> queryItemService.insert(
                queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, DynamicQueryOperator.LIKE)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("operator is not allowed");
    }

    @Test
    void shouldRevalidateFieldQueryConfigWhenCompilingQueryTemplate() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE, DynamicQueryOperator.EQ);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        PlatformQueryItem item = queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, DynamicQueryOperator.LIKE);
        item.setDefaultValue(null);
        queryItemService.insert(item);

        ResolvedField resolved = resolvedField(customerNameField);
        MetadataFieldConfig config = fieldConfig(resolved.metadataFieldId());
        config.setRelationId(resolved.relationId());
        config.setQueryable(true);
        config.setDefaultQueryOperator(DynamicQueryOperator.EQ);
        config.setQueryOperators(Set.of(DynamicQueryOperator.EQ.name()));
        fieldConfigService.insert(config);

        assertThatThrownBy(() -> queryItemService.compile(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("operator is not allowed");
    }

    @Test
    void shouldRevalidateFieldQueryConfigWhenEmptyQueryItemIsSkipped() {
        seedFieldType("string", FieldType.STRING, DynamicQueryOperator.LIKE);
        seedUiType("text", "string");
        String customerNameField = seedModuleField("crm.customer", "customer", "customerName", "customer_name", "string");
        String templateId = queryTemplateService.insert(queryTemplate("crm.customer", "default", true));
        queryItemService.insert(queryLeaf(templateId, TreeAbility.ROOT_ID, customerNameField, DynamicQueryOperator.LIKE));

        ResolvedField resolved = resolvedField(customerNameField);
        MetadataFieldConfig config = fieldConfig(resolved.metadataFieldId());
        config.setQueryable(false);
        fieldConfigService.insert(config);

        assertThatThrownBy(() -> queryItemService.compile(templateId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not queryable");
    }

    private void seedFieldType(String alias, FieldType type, DynamicQueryOperator defaultOperator,
                               DynamicQueryOperator... extraOperators) {
        FieldSpec fieldType = new FieldSpec();
        fieldType.setAlias(alias);
        fieldType.setTitle(alias);
        fieldType.setFieldType(type);
        fieldType.setDefaultQueryOperator(defaultOperator);
        Set<String> operators = new java.util.LinkedHashSet<>();
        operators.add(defaultOperator.name());
        for (DynamicQueryOperator operator : extraOperators) {
            operators.add(operator.name());
        }
        fieldType.setQueryOperators(operators);
        fieldTypeService.insert(fieldType);
    }

    private void seedUiType(String alias, String defaultFieldSpecAlias) {
        FieldUiControl uiType = new FieldUiControl();
        uiType.setAlias(alias);
        uiType.setTitle(alias);
        uiType.setDefaultFieldSpecAlias(defaultFieldSpecAlias);
        if ("date_range".equals(alias)) {
            uiType.setValueShape(FieldUiControlValueShape.COMPOSITE);
            uiType.setPrimaryValueKey("start");
        }
        fieldUiTypeService.insert(uiType);
    }

    private void seedUiTypeAttribute(String fieldUiControlAlias, String attributeAlias, String valueFieldSpecAlias,
                                     String defaultValue) {
        FieldUiControlProperty attribute = new FieldUiControlProperty();
        attribute.setFieldUiControlAlias(fieldUiControlAlias);
        attribute.setAttributeAlias(attributeAlias);
        attribute.setValueFieldSpecAlias(valueFieldSpecAlias);
        attribute.setDefaultValue(defaultValue);
        fieldUiTypeAttributeService.insert(attribute);
    }

    private void seedUiTypeFieldMapping(String fieldUiControlAlias, String valueKey) {
        FieldUiControlBinding mapping = new FieldUiControlBinding();
        mapping.setFieldUiControlAlias(fieldUiControlAlias);
        mapping.setValueKey(valueKey);
        mapping.setValueFieldSpecAlias("date");
        fieldUiTypeFieldMappingService.insert(mapping);
    }

    private String seedModuleField(String moduleAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldSpecAlias) {
        return seedModuleField(moduleAlias, metadataAlias, fieldName, columnName, fieldSpecAlias, false);
    }

    private String seedModuleField(String moduleAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldSpecAlias,
                                   boolean required) {
        return seedModuleField(moduleAlias, metadataAlias, fieldName, columnName, fieldSpecAlias, required,
                MetadataFieldForm.PHYSICAL);
    }

    private String seedModuleField(String moduleAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldSpecAlias,
                                   boolean required,
                                   MetadataFieldForm fieldForm) {
        String applicationAlias = moduleAlias.substring(0, moduleAlias.indexOf('.'));
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(applicationAlias);
        module.setAlias(moduleAlias);
        module.setTitle(moduleAlias);
        module.setParentId(TreeAbility.ROOT_ID);
        module.setModuleKind(ModuleKind.DYNAMIC);
        moduleService.insert(module);

        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(metadataAlias);
        metadata.setTitle(metadataAlias);
        String metadataId = metadataService.insert(metadata);

        MetadataField field = new MetadataField();
        field.setMetadataId(metadataId);
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(fieldSpecAlias);
        field.setTitle(fieldName);
        field.setRequired(required);
        field.setFieldForm(fieldForm);
        String fieldId = fieldService.insert(field);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationAlias(metadataAlias);
        String relationId = relationService.insert(relation);

        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId(fieldId);
        moduleField.setTitle(fieldName);
        return moduleFieldService.insert(moduleField);
    }

    private ResolvedField resolvedField(String moduleFieldId) {
        var resolved = moduleFieldService.resolve(moduleFieldId);
        return new ResolvedField(resolved.metadataFieldId(), resolved.relationId());
    }

    private MetadataFieldConfig fieldConfig(String fieldId) {
        MetadataFieldConfig config = new MetadataFieldConfig();
        config.setMetadataFieldId(fieldId);
        return config;
    }

    private record ResolvedField(String metadataFieldId, String relationId) {
    }

    private String addModuleField(String moduleAlias, String fieldName, String columnName, String fieldSpecAlias) {
        ModuleMetadataRelation relation = relationDao
                .query(Criteria.of().eq("moduleAlias", moduleAlias), new PageRequest(0, 1))
                .getFirst();
        MetadataField field = new MetadataField();
        field.setMetadataId(relation.getMetadataId());
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(fieldSpecAlias);
        field.setTitle(fieldName);
        String fieldId = fieldService.insert(field);

        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relation.getId());
        moduleField.setMetadataFieldId(fieldId);
        moduleField.setTitle(fieldName);
        return moduleFieldService.insert(moduleField);
    }

    private String addChildModuleField(String moduleAlias,
                                       String metadataAlias,
                                       String fieldName,
                                       String columnName,
                                       String fieldSpecAlias) {
        String applicationAlias = moduleAlias.substring(0, moduleAlias.indexOf('.'));
        ModuleMetadataRelation mainRelation = relationDao
                .query(Criteria.of()
                        .eq("moduleAlias", moduleAlias)
                        .eq("relationRole", RelationRole.MAIN), new PageRequest(0, 1))
                .getFirst();
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(metadataAlias);
        metadata.setTitle(metadataAlias);
        String metadataId = metadataService.insert(metadata);

        MetadataField field = new MetadataField();
        field.setMetadataId(metadataId);
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(fieldSpecAlias);
        field.setTitle(fieldName);
        String fieldId = fieldService.insert(field);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationAlias(metadataAlias);
        relation.setRelationRole(RelationRole.CHILD);
        relation.setParentMetadataId(mainRelation.getMetadataId());
        relation.setForeignKey("customerId");
        String relationId = relationService.insert(relation);

        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId(fieldId);
        moduleField.setTitle(fieldName);
        return moduleFieldService.insert(moduleField);
    }

    private PlatformUiSet uiSet(String moduleAlias, String alias, PlatformUiSetType setType, boolean defaultSet) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        uiSet.setSetType(setType);
        uiSet.setDefaultSet(defaultSet);
        return uiSet;
    }

    private PlatformUiConfig uiConfig(String uiSetId, PlatformUiClientType clientType, boolean published) {
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setUiSetId(uiSetId);
        uiConfig.setClientType(clientType);
        uiConfig.setPublished(published);
        return uiConfig;
    }

    private PlatformUiConfig uiConfigUpdate(PlatformUiConfig source) {
        PlatformUiConfig target = new PlatformUiConfig();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setUiSetId(source.getUiSetId());
        target.setClientType(source.getClientType());
        target.setLayoutJson(source.getLayoutJson());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setPublished(source.getPublished());
        return target;
    }

    private PlatformUiConfigField uiField(String uiConfigId, String moduleMetadataFieldId, String fieldUiControlAlias) {
        PlatformUiConfigField field = new PlatformUiConfigField();
        field.setUiConfigId(uiConfigId);
        field.setModuleMetadataFieldId(moduleMetadataFieldId);
        field.setFieldUiControlAlias(fieldUiControlAlias);
        return field;
    }

    private PlatformUiConfigField uiFieldUpdate(PlatformUiConfigField source) {
        PlatformUiConfigField target = new PlatformUiConfigField();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setUiConfigId(source.getUiConfigId());
        target.setModuleMetadataFieldId(source.getModuleMetadataFieldId());
        target.setFieldUiControlAlias(source.getFieldUiControlAlias());
        target.setVisible(source.getVisible());
        target.setVisibleWhen(source.getVisibleWhen());
        target.setRequiredOverride(source.getRequiredOverride());
        target.setReadOnly(source.getReadOnly());
        target.setReadOnlyWhen(source.getReadOnlyWhen());
        target.setPlaceholder(source.getPlaceholder());
        target.setDefaultValue(source.getDefaultValue());
        target.setWidth(source.getWidth());
        target.setMaxDisplayLines(source.getMaxDisplayLines());
        target.setAlign(source.getAlign());
        target.setFixedPosition(source.getFixedPosition());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private PlatformQueryTemplate queryTemplate(String moduleAlias, String alias, boolean defaultTemplate) {
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setModuleAlias(moduleAlias);
        template.setAlias(alias);
        template.setDefaultTemplate(defaultTemplate);
        return template;
    }

    private PlatformQueryTemplate queryTemplateUpdate(PlatformQueryTemplate source) {
        PlatformQueryTemplate target = new PlatformQueryTemplate();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setModuleAlias(source.getModuleAlias());
        target.setAlias(source.getAlias());
        target.setDefaultTemplate(source.getDefaultTemplate());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setPublished(source.getPublished());
        return target;
    }

    private PlatformQueryItem queryGroup(String templateId, String parentId, PlatformQueryGroupOperator groupOperator) {
        PlatformQueryItem item = new PlatformQueryItem();
        item.setQueryTemplateId(templateId);
        item.setParentId(parentId);
        item.setGroupOperator(groupOperator);
        return item;
    }

    private PlatformQueryItem queryLeaf(String templateId,
                                        String parentId,
                                        String moduleMetadataFieldId,
                                        DynamicQueryOperator operator) {
        PlatformQueryItem item = new PlatformQueryItem();
        item.setQueryTemplateId(templateId);
        item.setParentId(parentId);
        item.setModuleMetadataFieldId(moduleMetadataFieldId);
        item.setOperator(operator);
        item.setDefaultValue("default");
        return item;
    }

    private PlatformQueryItem queryItemUpdate(PlatformQueryItem source) {
        PlatformQueryItem target = new PlatformQueryItem();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setQueryTemplateId(source.getQueryTemplateId());
        target.setParentId(source.getParentId());
        target.setGroupOperator(source.getGroupOperator());
        target.setModuleMetadataFieldId(source.getModuleMetadataFieldId());
        target.setOperator(source.getOperator());
        target.setDefaultValue(source.getDefaultValue());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private List<CriteriaClause> clauses(Criteria criteria) {
        List<CriteriaClause> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private void collect(CriteriaGroup group, List<CriteriaClause> result) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = node(entry);
            if (node instanceof CriteriaClause clause) {
                result.add(clause);
            } else if (node instanceof CriteriaGroup childGroup) {
                collect(childGroup, result);
            }
        }
    }

    private Object node(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getNode");
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria node", e);
        }
    }
}
