package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.MenuVisibilityPolicyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformAssociationBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformActionBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItem;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigFieldService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigService;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedFieldUiControl;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuEntryBootstrapContractTest {
    private final TestMemoryDao<MenuScheme> schemeDao = new TestMemoryDao<>();
    private final TestMemoryDao<Menu> menuDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiSet> uiSetDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiConfig> uiConfigDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformUiConfigField> uiFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformQueryTemplate> queryTemplateDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformQueryItem> queryItemDao = new TestMemoryDao<>();

    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MenuSchemeService schemeService = new MenuSchemeService(schemeDao);
    private final PlatformUiSetService uiSetService = new PlatformUiSetService(uiSetDao, moduleService);
    private final PlatformUiConfigService uiConfigService = new PlatformUiConfigService(uiConfigDao, uiSetService);
    private final PlatformQueryTemplateService queryTemplateService =
            new PlatformQueryTemplateService(queryTemplateDao, moduleService);
    private final PlatformUiConfigFieldService uiFieldService = new PlatformUiConfigFieldService(
            uiFieldDao, uiConfigService, uiSetService, null, null, null, null);
    private final PlatformQueryItemService queryItemService =
            new PlatformQueryItemService(queryItemDao, queryTemplateService, null, null);
    private final MenuVisibilityPolicyService allowAllMenu = (moduleAlias, currentUser) -> true;
    private final MenuService menuService = new MenuService(menuDao, schemeService, moduleService,
            Optional.of(allowAllMenu), uiConfigService, uiSetService, queryTemplateService);
    private final PlatformPageConfigSnapshotService snapshotService = new PlatformPageConfigSnapshotService(
            uiSetService, uiConfigService, uiFieldService, queryTemplateService, queryItemService);
    private final PlatformPageBootstrapService bootstrapService =
            new PlatformPageBootstrapService(menuService, snapshotService);

    @BeforeEach
    void setUp() {
        try (TenantContext.Scope ignored = TenantContext.system("seed modules")) {
            insertModule("crm.customer");
            insertModule("crm.contract");
        }
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldValidateModuleMenuEntryConfig() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String schemeId = schemeService.insert(scheme());
            String uiConfigId = publishedUiConfig("crm.customer", "list", PlatformUiSetType.LIST, true);
            String templateId = queryTemplate("crm.customer", "default", true);
            Menu menu = moduleMenu(schemeId, "客户", "crm.customer");
            menu.setDefaultUiConfigId(uiConfigId);
            menu.setDefaultQueryTemplateId(templateId);
            menu.setEntryParamsJson("{\"source\":\"menu\"}");

            String menuId = menuService.insert(menu);

            Menu saved = menuService.select(menuId);
            assertThat(saved.getPageMode()).isEqualTo(MenuPageMode.LIST);
            assertThat(saved.getDefaultUiConfigId()).isEqualTo(uiConfigId);
            assertThat(saved.getDefaultQueryTemplateId()).isEqualTo(templateId);
            assertThat(saved.getEntryParamsJson()).contains("source");
        }
    }

    @Test
    void shouldRejectInvalidMenuEntryConfig() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String schemeId = schemeService.insert(scheme());
            String draftConfigId = publishedUiConfig("crm.customer", "draft", PlatformUiSetType.LIST, false);
            String formConfigId = publishedUiConfig("crm.customer", "form", PlatformUiSetType.FORM, true);
            String contractConfigId = publishedUiConfig("crm.contract", "list", PlatformUiSetType.LIST, true);
            String contractTemplateId = queryTemplate("crm.contract", "default", true);
            String disabledTemplateId = queryTemplate("crm.customer", "disabled", false, true, false);
            String draftTemplateId = queryTemplate("crm.customer", "draft", false, false, true);

            Menu draftMenu = moduleMenu(schemeId, "客户", "crm.customer");
            draftMenu.setDefaultUiConfigId(draftConfigId);
            assertThatThrownBy(() -> menuService.insert(draftMenu))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("published");

            Menu wrongUiModule = moduleMenu(schemeId, "客户", "crm.customer");
            wrongUiModule.setDefaultUiConfigId(contractConfigId);
            assertThatThrownBy(() -> menuService.insert(wrongUiModule))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("belong to module");

            Menu wrongQueryModule = moduleMenu(schemeId, "客户", "crm.customer");
            wrongQueryModule.setDefaultQueryTemplateId(contractTemplateId);
            assertThatThrownBy(() -> menuService.insert(wrongQueryModule))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("belong to module");

            Menu disabledQuery = moduleMenu(schemeId, "客户", "crm.customer");
            disabledQuery.setDefaultQueryTemplateId(disabledTemplateId);
            assertThatThrownBy(() -> menuService.insert(disabledQuery))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("enabled");

            Menu draftQuery = moduleMenu(schemeId, "客户", "crm.customer");
            draftQuery.setDefaultQueryTemplateId(draftTemplateId);
            assertThatThrownBy(() -> menuService.insert(draftQuery))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("published");

            Menu mismatchedPageMode = moduleMenu(schemeId, "客户", "crm.customer");
            mismatchedPageMode.setPageMode(MenuPageMode.LIST);
            mismatchedPageMode.setDefaultUiConfigId(formConfigId);
            assertThatThrownBy(() -> menuService.insert(mismatchedPageMode))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("page mode");

            Menu group = groupMenu(schemeId, "分组");
            group.setPageMode(MenuPageMode.LIST);
            assertThatThrownBy(() -> menuService.insert(group))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("low-code entry");
        }
    }

    @Test
    void shouldBootstrapByVisibleMenuOrModule() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (CurrentUserContext.Scope userScope = CurrentUserContext.use(
                    CurrentUser.tenantUser("u1", "alice", "tenant-a", "org-1"))) {
                String schemeId = schemeService.insert(scheme());
                String uiConfigId = publishedUiConfig("crm.customer", "list", PlatformUiSetType.LIST, true);
                String templateId = queryTemplate("crm.customer", "default", true);
                Menu menu = moduleMenu(schemeId, "客户", "crm.customer");
                menu.setDefaultUiConfigId(uiConfigId);
                menu.setDefaultQueryTemplateId(templateId);
                String menuId = menuService.insert(menu);

                PlatformPageBootstrap menuBootstrap = bootstrapService.bootstrapByMenu(menuId);
                PlatformPageBootstrap moduleBootstrap = bootstrapService.bootstrapByModule("crm.customer");

                assertThat(menuBootstrap.entry().menuId()).isEqualTo(menuId);
                assertThat(menuBootstrap.entry().defaultUiConfigId()).isEqualTo(uiConfigId);
                assertThat(menuBootstrap.entry().defaultQueryTemplateId()).isEqualTo(templateId);
                assertThat(moduleBootstrap.entry().menuId()).isEqualTo(menuId);
                assertThat(moduleBootstrap.entry().defaultUiConfigId()).isEqualTo(uiConfigId);
                assertThat(moduleBootstrap.clientType()).isEqualTo(PlatformUiClientType.WEB);
            }
        }
    }

    @Test
    void shouldResolveDefaultUiConfigByRequestedClientType() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (CurrentUserContext.Scope userScope = CurrentUserContext.use(
                    CurrentUser.tenantUser("u1", "alice", "tenant-a", "org-1"))) {
                String schemeId = schemeService.insert(scheme());
                String uiSetId = uiSet("crm.customer", "list_client", PlatformUiSetType.LIST);
                String webConfigId = publishedUiConfig(uiSetId, PlatformUiClientType.WEB, true);
                String appConfigId = publishedUiConfig(uiSetId, PlatformUiClientType.APP, true);
                Menu menu = moduleMenu(schemeId, "客户", "crm.customer");
                String menuId = menuService.insert(menu);

                PlatformPageBootstrap webBootstrap = bootstrapService.bootstrapByMenu(menuId);
                PlatformPageBootstrap appBootstrap = bootstrapService.bootstrapByMenu(menuId, PlatformUiClientType.APP);

                assertThat(webBootstrap.entry().defaultUiConfigId()).isEqualTo(webConfigId);
                assertThat(webBootstrap.clientType()).isEqualTo(PlatformUiClientType.WEB);
                assertThat(appBootstrap.entry().defaultUiConfigId()).isEqualTo(appConfigId);
                assertThat(appBootstrap.clientType()).isEqualTo(PlatformUiClientType.APP);
            }
        }
    }

    @Test
    void shouldRejectRequestedDefaultUiConfigWhenClientTypeDoesNotMatch() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (CurrentUserContext.Scope userScope = CurrentUserContext.use(
                    CurrentUser.tenantUser("u1", "alice", "tenant-a", "org-1"))) {
                String schemeId = schemeService.insert(scheme());
                String webConfigId = publishedUiConfig("crm.customer", "list_web",
                        PlatformUiSetType.LIST, PlatformUiClientType.WEB, true);
                Menu menu = moduleMenu(schemeId, "客户", "crm.customer");
                menu.setDefaultUiConfigId(webConfigId);
                String menuId = menuService.insert(menu);

                assertThatThrownBy(() -> bootstrapService.bootstrapByMenu(menuId, PlatformUiClientType.APP))
                        .isInstanceOf(PlatformException.class)
                        .hasMessageContaining("client type");
            }
        }
    }

    @Test
    void shouldResolveOnlyRequestedClientUiFields() {
        MenuService mockedMenuService = mock(MenuService.class);
        PlatformPageConfigSnapshotService mockedSnapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService mockedModuleFieldService = mock(ModuleMetadataFieldService.class);
        FieldUiControlService mockedFieldUiControlService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService mockedAttributeService = mock(FieldUiControlPropertyService.class);
        FieldUiControlBindingService mockedMappingService = mock(FieldUiControlBindingService.class);
        PlatformPageBootstrapService service = new PlatformPageBootstrapService(
                mockedMenuService, mockedSnapshotService, mockedModuleFieldService, mockedFieldUiControlService,
                mockedAttributeService, mockedMappingService);
        Menu menu = moduleMenu("scheme-1", "客户", "crm.customer");
        menu.setId("menu-1");
        when(mockedMenuService.currentUserVisibleMenu("menu-1")).thenReturn(menu);
        PlatformUiSet uiSet = uiSetRecord("set-1", "crm.customer", PlatformUiSetType.LIST);
        PlatformUiConfig webConfig = uiConfigRecord("ui-web", "set-1", PlatformUiClientType.WEB);
        PlatformUiConfig appConfig = uiConfigRecord("ui-app", "set-1", PlatformUiClientType.APP);
        PlatformUiConfigField webField = uiField("ui-web", "field-web");
        PlatformUiConfigField appField = uiField("ui-app", "field-app");
        when(mockedSnapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer",
                java.util.List.of(uiSet),
                java.util.List.of(webConfig, appConfig),
                java.util.List.of(webField, appField),
                java.util.List.of(),
                java.util.List.of()
        ));
        when(mockedModuleFieldService.resolve("field-web")).thenReturn(resolvedField("field-web", "webName"));
        when(mockedModuleFieldService.resolve("field-app")).thenReturn(resolvedField("field-app", "appName"));
        FieldUiControl textType = new FieldUiControl();
        textType.setAlias("text");
        textType.setTitle("Text");
        textType.setDefaultFieldSpecAlias("text");
        FieldUiControlProperty placeholder = new FieldUiControlProperty();
        placeholder.setFieldUiControlAlias("text");
        placeholder.setAttributeAlias("placeholder");
        placeholder.setTitle("Placeholder");
        placeholder.setValueFieldSpecAlias("text");
        placeholder.setDefaultValue("请输入");
        FieldUiControlBinding labelMapping = new FieldUiControlBinding();
        labelMapping.setFieldUiControlAlias("text");
        labelMapping.setValueKey("label");
        labelMapping.setValueFieldSpecAlias("string");
        labelMapping.setTitle("Label");
        when(mockedFieldUiControlService.listEnabledByAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of(textType));
        when(mockedAttributeService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of(placeholder));
        when(mockedMappingService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of(labelMapping));

        PlatformPageBootstrap appBootstrap = service.bootstrapByMenu("menu-1", PlatformUiClientType.APP);

        assertThat(appBootstrap.resolvedConfig().uiFields())
                .extracting(PlatformResolvedUiField::moduleMetadataFieldId)
                .containsExactly("field-app");
        assertThat(appBootstrap.entry().defaultUiConfigId()).isEqualTo("ui-app");
        assertThat(appBootstrap.resolvedConfig().fieldUiControls())
                .extracting("alias")
                .containsExactly("text");
        assertThat(appBootstrap.resolvedConfig().fieldUiControls().getFirst().properties())
                .extracting("attributeAlias")
                .containsExactly("placeholder");
        assertThat(appBootstrap.resolvedConfig().fieldUiControls().getFirst().bindings())
                .extracting("valueKey")
                .containsExactly("label");
    }

    @Test
    void shouldResolveAssociationBlocksFromClientLayout() {
        MenuService mockedMenuService = mock(MenuService.class);
        PlatformPageConfigSnapshotService mockedSnapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService mockedModuleFieldService = mock(ModuleMetadataFieldService.class);
        FieldUiControlService mockedFieldUiControlService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService mockedAttributeService = mock(FieldUiControlPropertyService.class);
        FieldUiControlBindingService mockedMappingService = mock(FieldUiControlBindingService.class);
        DynamicRecordService mockedRecordService = mock(DynamicRecordService.class);
        FieldSpecService mockedFieldSpecService = mock(FieldSpecService.class);
        PlatformPageBootstrapService service = new PlatformPageBootstrapService(
                mockedMenuService, mockedSnapshotService, mockedModuleFieldService, mockedFieldUiControlService,
                mockedAttributeService, mockedMappingService,
                mockedRecordService, mockedFieldSpecService);
        FieldSpec textSpec = new FieldSpec();
        textSpec.setFieldType(FieldType.STRING);
        when(mockedFieldSpecService.requireFieldType("text")).thenReturn(textSpec);
        Menu menu = moduleMenu("scheme-1", "客户", "crm.customer");
        menu.setId("menu-1");
        menu.setPageMode(MenuPageMode.DETAIL);
        when(mockedMenuService.currentUserVisibleMenu("menu-1")).thenReturn(menu);
        PlatformUiSet detailSet = uiSetRecord("set-1", "crm.customer", PlatformUiSetType.DETAIL);
        detailSet.setDefaultSet(true);
        PlatformUiConfig webConfig = uiConfigRecord("ui-detail-web", "set-1", PlatformUiClientType.WEB);
        webConfig.setLayoutJson("""
                {
                  "blocks": [
                    {
                      "type": "associationView",
                      "key": "contracts",
                      "viewCode": "contracts",
                      "title": "合同",
                      "uiConfigId": "contract-list",
                      "queryTemplateId": "contract-default"
                    },
                    {
                      "type": "dialog",
                      "key": "submitDialog",
                      "actionCode": "submitDialog",
                      "title": "提交",
                      "position": "recordToolbar"
                    },
                    {
                      "type": "localEdit",
                      "key": "baseInfo",
                      "actionCode": "editBaseInfo",
                      "title": "编辑资料",
                      "position": "recordToolbar",
                      "targetUiConfigId": "ui-local-edit-web",
                      "width": 720,
                      "height": 520,
                      "refresh": {"list": false, "detail": true}
                    },
                    {
                      "type": "taskPanel",
                      "key": "contractReady",
                      "title": "合同齐备",
                      "checkType": "ASSOCIATION_VIEW",
                      "associationViewCode": "contracts",
                      "diagnosticPath": "/crm.customer/view/{id}/associations/contracts/query"
                    }
                  ]
                }
                """);
        PlatformUiConfig appConfig = uiConfigRecord("ui-detail-app", "set-1", PlatformUiClientType.APP);
        appConfig.setLayoutJson("""
                {
                  "blocks": [
                    {"type": "associationView", "viewCode": "appContracts"}
                  ]
                }
                """);
        PlatformUiSet localEditSet = uiSetRecord("local-edit-set", "crm.customer", PlatformUiSetType.FORM);
        localEditSet.setDefaultSet(false);
        PlatformUiConfig localEditConfig = uiConfigRecord("ui-local-edit-web", "local-edit-set",
                PlatformUiClientType.WEB);
        localEditConfig.setLayoutJson("""
                {"blocks":[{"type":"localEdit","actionCode":"editBaseInfo","targetUiConfigId":"ui-local-edit-web"}]}
                """);
        PlatformUiConfigField localEditField = uiField("ui-local-edit-web", "customer-base-field");
        PlatformUiSet contractListSet = uiSetRecord("contract-list-set", "crm.contract", PlatformUiSetType.LIST);
        PlatformUiConfig contractListConfig = uiConfigRecord("contract-list", "contract-list-set",
                PlatformUiClientType.WEB);
        PlatformUiConfigField contractSummaryField = uiField("contract-list", "contract-summary-field");
        contractSummaryField.setVisible(true);
        contractSummaryField.setWidth(240);
        contractSummaryField.setMaxDisplayLines(2);
        when(mockedSnapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer",
                java.util.List.of(detailSet, localEditSet),
                java.util.List.of(webConfig, appConfig, localEditConfig),
                java.util.List.of(localEditField),
                java.util.List.of(),
                java.util.List.of()
        ));
        when(mockedSnapshotService.snapshot("crm.contract")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.contract",
                java.util.List.of(contractListSet),
                java.util.List.of(contractListConfig),
                java.util.List.of(contractSummaryField),
                java.util.List.of(),
                java.util.List.of()
        ));
        when(mockedModuleFieldService.resolve("contract-summary-field"))
                .thenReturn(resolvedContractField("contract-summary-field", "summary"));
        when(mockedModuleFieldService.resolve("customer-base-field"))
                .thenReturn(resolvedField("customer-base-field", "customerName"));
        FieldUiControl textControl = new FieldUiControl();
        textControl.setAlias("text");
        textControl.setRendererType(ViewControlType.TEXT);
        when(mockedFieldUiControlService.listEnabledByAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of(textControl));
        when(mockedAttributeService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of());
        when(mockedMappingService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of());
        when(mockedRecordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer", "客户", "customer", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(new DynamicAssociationViewDescriptor("contracts", "customer",
                "crm.contract", "contract", AssociationViewDisplayMode.INLINE_LIST, "contracts", null,
                EntityViewType.LIST, true))));
        when(mockedRecordService.describe("crm.contract")).thenReturn(new DynamicModuleDescriptor(
                "crm.contract", "合同", "contract", java.util.List.of(), java.util.List.of(
                new DynamicEntityDescriptor("contract", "合同", java.util.Set.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of())),
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PlatformPageBootstrap bootstrap = service.bootstrapByMenu("menu-1", PlatformUiClientType.WEB);

        assertThat(bootstrap.resolvedConfig().associationBlocks())
                .extracting(PlatformAssociationBlock::viewCode)
                .containsExactly("contracts");
        PlatformAssociationBlock block = bootstrap.resolvedConfig().associationBlocks().getFirst();
        assertThat(block.uiConfigId()).isEqualTo("ui-detail-web");
        assertThat(block.key()).isEqualTo("contracts");
        assertThat(block.targetUiConfigId()).isEqualTo("contract-list");
        assertThat(block.queryTemplateId()).isEqualTo("contract-default");
        assertThat(block.queryPath()).isEqualTo("/crm.customer/view/{id}/associations/contracts/query");
        assertThat(block.relation()).isNotNull();
        assertThat(block.relation().hasExecutableQueryContract()).isTrue();
        assertThat(block.relation().targetModuleAlias()).isEqualTo("crm.contract");
        assertThat(block.relation().parentBinding()).isEqualTo("contracts");
        assertThat(block.relation().queryContract().targetUiConfigId()).isEqualTo("contract-list");
        assertThat(block.relation().queryContract().listProjection().uiConfigId()).isEqualTo("contract-list");
        assertThat(block.relation().queryContract().listProjection().fields())
                .extracting(field -> field.fieldName(), field -> field.title(), field -> field.width(),
                        field -> field.maxDisplayLines())
                .containsExactly(tuple("summary", "摘要", 240, 2));
        assertThat(block.relation().queryContract().querySchema())
                .extracting(schema -> schema.scopeName(), schema -> schema.entityAlias())
                .containsExactly("crm.contract", "contract");
        assertThat(bootstrap.resolvedConfig().actionBlocks())
                .extracting(PlatformActionBlock::actionCode)
                .containsExactly("submitDialog", "editBaseInfo");
        PlatformActionBlock actionBlock = bootstrap.resolvedConfig().actionBlocks().getFirst();
        assertThat(actionBlock.type()).isEqualTo("dialog");
        assertThat(actionBlock.position()).isEqualTo("recordToolbar");
        PlatformActionBlock localEditBlock = bootstrap.resolvedConfig().actionBlocks().get(1);
        assertThat(localEditBlock.type()).isEqualTo("localEdit");
        assertThat(localEditBlock.targetUiConfigId()).isEqualTo("ui-local-edit-web");
        assertThat(localEditBlock.localEditForm()).isNotNull();
        assertThat(localEditBlock.localEditForm().uiConfigId()).isEqualTo("ui-local-edit-web");
        assertThat(localEditBlock.localEditForm().fields())
                .extracting(PlatformResolvedUiField::fieldName, PlatformResolvedUiField::valueType)
                .containsExactly(tuple("customerName", "STRING"));
        assertThat(localEditBlock.localEditForm().fieldUiControls())
                .extracting(PlatformResolvedFieldUiControl::alias, PlatformResolvedFieldUiControl::rendererType)
                .containsExactly(tuple("text", ViewControlType.TEXT));
        assertThat(localEditBlock.localEditForm().submitContract().recordVersionRequired()).isTrue();
        assertThat(localEditBlock.localEditForm().submitContract().fieldNamesRequired()).isTrue();
        assertThat(localEditBlock.localEditForm().submitContract().uiConfigIdPayloadKey()).isEqualTo("uiConfigId");
        assertThat(localEditBlock.submitPath()).isEqualTo("/crm.customer/editBaseInfo/{recordId}");
        assertThat(localEditBlock.refreshStrategy().list()).isFalse();
        assertThat(localEditBlock.refreshStrategy().detail()).isTrue();
        assertThat(localEditBlock.width()).isEqualTo(720);
        assertThat(localEditBlock.height()).isEqualTo(520);
        assertThat(bootstrap.resolvedConfig().taskBlocks())
                .extracting(PlatformTaskBlock::key)
                .containsExactly("contractReady");
        PlatformTaskBlock taskBlock = bootstrap.resolvedConfig().taskBlocks().getFirst();
        assertThat(taskBlock.checkType()).isEqualTo(PlatformTaskCheckType.ASSOCIATION_VIEW);
        assertThat(taskBlock.associationViewCode()).isEqualTo("contracts");
        assertThat(taskBlock.diagnosticPath()).isEqualTo("/crm.customer/view/{id}/associations/contracts/query");
    }

    @Test
    void shouldRejectAssociationRelationWhenTargetUiConfigIsNotList() {
        MenuService mockedMenuService = mock(MenuService.class);
        PlatformPageConfigSnapshotService mockedSnapshotService = mock(PlatformPageConfigSnapshotService.class);
        DynamicRecordService mockedRecordService = mock(DynamicRecordService.class);
        PlatformPageBootstrapService service = new PlatformPageBootstrapService(
                mockedMenuService, mockedSnapshotService, mock(ModuleMetadataFieldService.class), null, null, null,
                mockedRecordService);
        Menu menu = moduleMenu("scheme-1", "客户", "crm.customer");
        menu.setId("menu-1");
        menu.setPageMode(MenuPageMode.DETAIL);
        when(mockedMenuService.currentUserVisibleMenu("menu-1")).thenReturn(menu);
        PlatformUiSet detailSet = uiSetRecord("detail-set", "crm.customer", PlatformUiSetType.DETAIL);
        PlatformUiConfig detailConfig = uiConfigRecord("customer-detail", "detail-set", PlatformUiClientType.WEB);
        detailConfig.setLayoutJson("""
                {"blocks":[{"type":"associationView","viewCode":"contracts","uiConfigId":"contract-form"}]}
                """);
        when(mockedSnapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer", java.util.List.of(detailSet), java.util.List.of(detailConfig), java.util.List.of(),
                java.util.List.of(), java.util.List.of()));
        PlatformUiSet formSet = uiSetRecord("contract-form-set", "crm.contract", PlatformUiSetType.FORM);
        PlatformUiConfig formConfig = uiConfigRecord("contract-form", "contract-form-set", PlatformUiClientType.WEB);
        when(mockedSnapshotService.snapshot("crm.contract")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.contract", java.util.List.of(formSet), java.util.List.of(formConfig), java.util.List.of(),
                java.util.List.of(), java.util.List.of()));
        when(mockedRecordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer", "客户", "customer", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(new DynamicAssociationViewDescriptor("contracts", "customer",
                "crm.contract", "contract", AssociationViewDisplayMode.INLINE_LIST, "contracts", null,
                EntityViewType.LIST, true))));

        assertThatThrownBy(() -> service.bootstrapByMenu("menu-1", PlatformUiClientType.WEB))
                .isInstanceOf(PlatformException.class)
                .hasMessage("Default UI config type must match page mode: LIST");
    }

    @Test
    void shouldRejectBootstrapWhenMenuIsNotVisible() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (CurrentUserContext.Scope userScope = CurrentUserContext.use(
                    CurrentUser.tenantUser("u1", "alice", "tenant-a", "org-1"))) {
                String schemeId = schemeService.insert(scheme());
                Menu menu = moduleMenu(schemeId, "客户", "crm.customer");
                String menuId = menuService.insert(menu);
                MenuService deniedMenuService = new MenuService(menuDao, schemeService, moduleService,
                        Optional.of((moduleAlias, currentUser) -> false), uiConfigService, uiSetService,
                        queryTemplateService);
                PlatformPageBootstrapService deniedBootstrapService =
                        new PlatformPageBootstrapService(deniedMenuService, snapshotService);

                assertThatThrownBy(() -> deniedBootstrapService.bootstrapByMenu(menuId))
                        .isInstanceOf(PlatformException.class)
                        .hasMessageContaining("not visible");
                assertThatThrownBy(() -> deniedBootstrapService.bootstrapByModule("crm.customer"))
                        .isInstanceOf(PlatformException.class)
                        .hasMessageContaining("not visible");
            }
        }
    }

    @Test
    void shouldRejectBootstrapWhenUiTypeCatalogIsMissing() {
        MenuService mockedMenuService = mock(MenuService.class);
        PlatformPageConfigSnapshotService mockedSnapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService mockedModuleFieldService = mock(ModuleMetadataFieldService.class);
        FieldUiControlService mockedFieldUiControlService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService mockedAttributeService = mock(FieldUiControlPropertyService.class);
        FieldUiControlBindingService mockedMappingService = mock(FieldUiControlBindingService.class);
        PlatformPageBootstrapService service = new PlatformPageBootstrapService(
                mockedMenuService, mockedSnapshotService, mockedModuleFieldService, mockedFieldUiControlService,
                mockedAttributeService, mockedMappingService);
        Menu menu = moduleMenu("scheme-1", "客户", "crm.customer");
        menu.setId("menu-1");
        when(mockedMenuService.currentUserVisibleMenu("menu-1")).thenReturn(menu);
        PlatformUiSet uiSet = uiSetRecord("set-1", "crm.customer", PlatformUiSetType.LIST);
        PlatformUiConfig webConfig = uiConfigRecord("ui-web", "set-1", PlatformUiClientType.WEB);
        PlatformUiConfigField webField = uiField("ui-web", "field-web");
        when(mockedSnapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer",
                java.util.List.of(uiSet),
                java.util.List.of(webConfig),
                java.util.List.of(webField),
                java.util.List.of(),
                java.util.List.of()
        ));
        when(mockedModuleFieldService.resolve("field-web")).thenReturn(resolvedField("field-web", "webName"));
        when(mockedFieldUiControlService.listEnabledByAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of());
        when(mockedAttributeService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of());
        when(mockedMappingService.listByFieldUiControlAliases(java.util.List.of("text")))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.bootstrapByMenu("menu-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("field UI controls");
    }

    private void insertModule(String moduleAlias) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        module.setAlias(moduleAlias);
        module.setTitle(moduleAlias);
        module.setParentId(TreeAbility.ROOT_ID);
        module.setModuleKind(ModuleKind.DYNAMIC);
        moduleService.insert(module);
    }

    private MenuScheme scheme() {
        MenuScheme scheme = new MenuScheme();
        scheme.setAlias("default");
        scheme.setScopeType(MenuScopeType.TENANT);
        scheme.setTitle("默认");
        return scheme;
    }

    private Menu moduleMenu(String schemeId, String title, String moduleAlias) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setParentId(TreeAbility.ROOT_ID);
        menu.setTitle(title);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias(moduleAlias);
        return menu;
    }

    private Menu groupMenu(String schemeId, String title) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setParentId(TreeAbility.ROOT_ID);
        menu.setTitle(title);
        return menu;
    }

    private String publishedUiConfig(String moduleAlias, String alias, PlatformUiSetType setType, boolean published) {
        return publishedUiConfig(moduleAlias, alias, setType, PlatformUiClientType.WEB, published);
    }

    private String publishedUiConfig(String moduleAlias, String alias, PlatformUiSetType setType,
                                     PlatformUiClientType clientType, boolean published) {
        return publishedUiConfig(uiSet(moduleAlias, alias, setType), clientType, published);
    }

    private String uiSet(String moduleAlias, String alias, PlatformUiSetType setType) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        uiSet.setSetType(setType);
        uiSet.setDefaultSet(true);
        return uiSetService.insert(uiSet);
    }

    private String publishedUiConfig(String uiSetId, PlatformUiClientType clientType, boolean published) {
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setUiSetId(uiSetId);
        uiConfig.setClientType(clientType);
        String id = uiConfigService.insert(uiConfig);
        if (published) {
            PlatformUiConfig saved = uiConfigService.select(id);
            saved.setPublished(true);
            uiConfigDao.updateById(saved);
        }
        return id;
    }

    private String queryTemplate(String moduleAlias, String alias, boolean defaultTemplate) {
        return queryTemplate(moduleAlias, alias, defaultTemplate, true, true);
    }

    private String queryTemplate(String moduleAlias, String alias, boolean defaultTemplate,
                                 boolean published, boolean enabled) {
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setModuleAlias(moduleAlias);
        template.setAlias(alias);
        template.setDefaultTemplate(defaultTemplate);
        template.setEnabled(enabled);
        String id = queryTemplateService.insert(template);
        if (published) {
            PlatformQueryTemplate saved = queryTemplateService.select(id);
            saved.setPublished(true);
            queryTemplateDao.updateById(saved);
        }
        return id;
    }

    private PlatformUiSet uiSetRecord(String id, String moduleAlias, PlatformUiSetType setType) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(id);
        uiSet.setSetType(setType);
        uiSet.setDefaultSet(true);
        return uiSet;
    }

    private PlatformUiConfig uiConfigRecord(String id, String uiSetId, PlatformUiClientType clientType) {
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId(id);
        uiConfig.setUiSetId(uiSetId);
        uiConfig.setClientType(clientType);
        uiConfig.setPublished(true);
        return uiConfig;
    }

    private PlatformUiConfigField uiField(String uiConfigId, String moduleFieldId) {
        PlatformUiConfigField field = new PlatformUiConfigField();
        field.setUiConfigId(uiConfigId);
        field.setModuleMetadataFieldId(moduleFieldId);
        field.setFieldUiControlAlias("text");
        return field;
    }

    private ResolvedModuleMetadataField resolvedField(String moduleFieldId, String fieldName) {
        return new ResolvedModuleMetadataField(
                moduleFieldId,
                "crm.customer",
                "rel-main",
                "main",
                RelationRole.MAIN,
                "metadata-1",
                "customer",
                "客户",
                "metadata-field-" + fieldName,
                fieldName,
                fieldName,
                fieldName,
                "text",
                MetadataFieldForm.PHYSICAL
        );
    }

    private ResolvedModuleMetadataField resolvedContractField(String moduleFieldId, String fieldName) {
        return new ResolvedModuleMetadataField(
                moduleFieldId,
                "crm.contract",
                null,
                null,
                RelationRole.MAIN,
                "metadata-contract",
                "contract",
                "合同",
                "metadata-field-" + fieldName,
                fieldName,
                fieldName,
                "摘要",
                "text",
                MetadataFieldForm.PHYSICAL
        );
    }
}
