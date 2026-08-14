package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFormulaException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryKind;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldValueValidator;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItem;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuScopeType;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = PlatformDynamicRuntimeRefresherIT.TestApplication.class)
class PlatformDynamicRuntimeRefresherIT extends PlatformPostgresIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.default-schema", () -> "public");
    }

    private final IDatabaseOperations<?> operations;
    private final DynamicSchemaService schemaService;

    @Autowired
    PlatformDynamicRuntimeRefresherIT(IDatabaseOperations<?> operations, DynamicSchemaService schemaService) {
        this.operations = operations;
        this.schemaService = schemaService;
    }

    @Test
    void shouldRefreshM2PlatformConfigAndRunDynamicCrudByModuleAndMetadataAlias() {
        PlatformServices services = platformServices();
        services.applicationService.insert(application("crm"));
        services.moduleService.insert(module("crm.customer"));
        String categoryId = services.categoryService.insert(
                category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY));
        services.itemService.insert(item(categoryId, "customer_status", "active"));
        String customerMetadataId = services.metadataService.insert(metadata("crm", "customer", "platform_refresh_customer_it"));
        String contactMetadataId = services.metadataService.insert(metadata("crm", "customer_contact", "platform_refresh_contact_it"));
        services.fieldService.insert(titleField(customerMetadataId));
        services.fieldService.insert(field(customerMetadataId, "code", "code", FieldType.STRING));
        MetadataField status = field(customerMetadataId, "status", "status", FieldType.STRING);
        services.fieldService.insert(status);
        MetadataFieldConfig statusConfig = fieldConfig(status.getId());
        statusConfig.setDictionaryCategoryAlias("customer_status");
        statusConfig.setDefaultValue("active");
        statusConfig.setValidationRegex("[a-z_]+");
        statusConfig.setCopyable(false);
        services.fieldConfigService.insert(statusConfig);
        MetadataField serverCode = field(customerMetadataId, "serverCode", "server_code", FieldType.STRING);
        services.fieldService.insert(serverCode);
        MetadataFieldConfig serverCodeConfig = fieldConfig(serverCode.getId());
        serverCodeConfig.setDefaultValue("SYS");
        serverCodeConfig.setWriteProtected(true);
        services.fieldConfigService.insert(serverCodeConfig);
        services.fieldService.insert(titleField(contactMetadataId));
        services.fieldService.insert(field(contactMetadataId, "quantity", "quantity", FieldType.INTEGER));
        services.fieldService.insert(field(contactMetadataId, "lineAmount", "line_amount", FieldType.INTEGER));
        MetadataField customerIdField = field(contactMetadataId, "customerId", "customer_id", FieldType.STRING);
        services.fieldService.insert(customerIdField);
        MetadataFieldReferenceConfig customerReference = referenceConfig(customerIdField.getId(), customerMetadataId);
        customerReference.setTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.RESTRICT);
        customerReference.setProjectionMappings("title:customerTitle,code:customerCode");
        services.referenceConfigService.insert(customerReference);
        String mainRelationId = services.relationService.insert(mainRelation("crm.customer", customerMetadataId));
        services.relationService.insert(childRelation("crm.customer", contactMetadataId, customerMetadataId));
        ModuleMetadataFormulaRule titleRequiredRule = formulaRule(mainRelationId, "titleRequired", "{title} != ''");
        titleRequiredRule.setMessageTemplate("客户名称不能为空");
        services.formulaRuleService.insert(titleRequiredRule);
        ModuleMetadataFormulaRule lineAmountRule = formulaRule(mainRelationId, "lineAmountCalc",
                "SUM({contacts.lineAmount} = {contacts.quantity} * 2)");
        lineAmountRule.setRuleKind(FormulaRuleKind.CALCULATION);
        services.formulaRuleService.insert(lineAmountRule);
        String formViewId = services.viewService.insert(metadataView(mainRelationId, EntityViewType.FORM, "客户表单"));
        MetadataViewField statusViewField = metadataViewField(formViewId, status.getId());
        statusViewField.setControlType(ViewControlType.SELECT);
        services.viewFieldService.insert(statusViewField);
        PlatformModuleAction createAction = moduleAction("crm.customer", "customer", "create");
        services.actionService.insert(createAction);
        PlatformModuleAction submitAction = moduleAction("crm.customer", "customer", "submit");
        submitAction.setAvailableExpression("{title} != ''");
        submitAction.setUnavailableMessage("客户名称不能为空");
        services.actionService.insert(submitAction);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String schemeId = services.schemeService.insert(menuScheme("main"));
            services.menuService.insert(moduleMenu(schemeId, "客户", "crm.customer"));
            assertThat(services.menuService.rootMenus(schemeId))
                    .extracting(Menu::getModuleAlias)
                    .containsExactly("crm.customer");
        }
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .fieldValueValidator(new DictionaryFieldValueValidator(services.itemService))
                .build();
        PlatformDynamicRuntimeRefresher refresher = new PlatformDynamicRuntimeRefresher(
                new PlatformModuleDefinitionCompiler(
                        services.moduleService,
                        services.metadataService,
                        services.fieldService,
                        services.fieldDefinitionCompiler,
                        services.referenceConfigService,
                        services.relationService,
                        services.viewService,
                        services.viewFieldService,
                        services.actionService,
                        services.formulaRuleService
                ),
                new DynamicModuleRuntimeRefresher(schemaService, runtime)
        );

        refresher.refresh("crm.customer");
        DynamicRecordService runtimeService = new DynamicRecordService(runtime);
        DynamicEntityOperations customer = runtimeService.entity("crm.customer", "customer");
        DynamicRecord contact = runtime.newRecord("crm.customer", "customer_contact")
                .setValue("title", "张三")
                .setValue("quantity", 3);
        DynamicRecord record = customer.newRecord()
                .setValue("title", "客户A")
                .setValue("code", "C-001")
                .setValue("status", "active")
                .setChildren("contacts", List.of(contact));
        String id = customer.create(record);
        DynamicRecord defaulted = customer.newRecord()
                .setValue("title", "客户B")
                .setValue("code", "C-002");
        String defaultedId = customer.create(defaulted);

        DynamicRecord selected = customer.select(id);

        assertThatThrownBy(() -> runtime.validateReferenceTargetDeletion(
                ReferenceTarget.of("crm.customer", "customer"), id))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("cannot make reference target unavailable");

        assertThat(categoryId).isNotBlank();
        assertThat(runtime.registry().requireModule("crm.customer").mainEntityAlias()).isEqualTo("customer");
        assertThat(runtimeService.module("crm.customer").action("create").actionAuth()).isTrue();
        assertThat(customer.action("create").actionAuth()).isTrue();
        assertThat(runtimeService.module("crm.customer").action("submit").availabilityCondition()).isTrue();
        assertThat(runtimeService.module("crm.customer").actionAvailability("submit", selected).available()).isTrue();
        DynamicRecord unnamed = customer.newRecord()
                .setValue("title", "");
        assertThat(runtimeService.module("crm.customer").actionAvailability("submit", unnamed))
                .satisfies(availability -> {
                    assertThat(availability.available()).isFalse();
                    assertThat(availability.message()).isEqualTo("客户名称不能为空");
                });
        assertThat(customer.view(EntityViewType.FORM).title()).isEqualTo("客户表单");
        assertThat(customer.view(EntityViewType.FORM).fields())
                .extracting(field -> field.fieldName())
                .contains("status");
        assertThat(customer.view(EntityViewType.FORM).fields().stream()
                .filter(field -> field.fieldName().equals("status"))
                .findFirst())
                .get()
                .extracting(field -> field.controlType())
                .isEqualTo(ViewControlType.SELECT);
        assertThat(customer.describe().fields().stream()
                .filter(field -> field.fieldName().equals("status"))
                .findFirst())
                .get()
                .satisfies(field -> {
                    assertThat(field.defaultValue()).isEqualTo("active");
                    assertThat(field.validationRegex()).isEqualTo("[a-z_]+");
                    assertThat(field.copyable()).isFalse();
                });
        assertThat(customer.describe().fields().stream()
                .filter(field -> field.fieldName().equals("serverCode"))
                .findFirst())
                .get()
                .satisfies(field -> {
                    assertThat(field.defaultValue()).isEqualTo("SYS");
                    assertThat(field.writeProtected()).isTrue();
                });
        assertThat(runtimeService.module("crm.customer").associationViews())
                .extracting(view -> view.code())
                .contains("contacts", "customerId");
        assertThat(services.itemService.resolveItem("crm", "customer_status", "active").getCode()).isEqualTo("active");
        assertThat(selected.getValue("title")).isEqualTo("客户A");
        assertThat(selected.getValue("status")).isEqualTo("active");
        assertThat(selected.getValue("serverCode")).isEqualTo("SYS");
        assertThat(customer.select(defaultedId).getValue("status")).isEqualTo("active");
        assertThat(selected.getChildren("contacts"))
                .hasSize(1)
                .first()
                .extracting(child -> child.getValue("title"))
                .isEqualTo("张三");
        assertThat(selected.getChildren("contacts").getFirst().getValue("customerTitle")).isEqualTo("客户A");
        assertThat(selected.getChildren("contacts").getFirst().getValue("customerCode")).isEqualTo("C-001");
        assertThat(selected.getChildren("contacts").getFirst().getValue("lineAmount")).isEqualTo(6);
        assertThat(customer.list(Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10)))
                .extracting(item -> item.getValue("title"))
                .containsExactly("客户A");

        DynamicRecord invalid = customer.newRecord()
                .setValue("title", "客户B")
                .setValue("code", "C-002")
                .setValue("status", "frozen");
        assertThatThrownBy(() -> customer.create(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionary code");
        DynamicRecord protectedWrite = customer.newRecord()
                .setValue("title", "客户C")
                .setValue("code", "C-003")
                .setValue("serverCode", "MANUAL");
        assertThatThrownBy(() -> customer.create(protectedWrite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write protected");
        DynamicRecord invalidByFormula = customer.newRecord()
                .setValue("title", "")
                .setValue("code", "C-004")
                .setValue("status", "active");
        assertThatThrownBy(() -> customer.create(invalidByFormula))
                .isInstanceOf(DynamicFormulaException.class)
                .hasMessageContaining("客户名称不能为空");
    }

    private PlatformServices platformServices() {
        TestMemoryDao<Application> applicationDao = new TestMemoryDao<>();
        TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
        TestMemoryDao<Metadata> metadataDao = new TestMemoryDao<>();
        TestMemoryDao<MetadataField> fieldDao = new TestMemoryDao<>();
        TestMemoryDao<FieldSpec> fieldTypeDao = new TestMemoryDao<>();
        TestMemoryDao<MetadataFieldConfig> fieldConfigDao = new TestMemoryDao<>();
        TestMemoryDao<MetadataFieldReferenceConfig> referenceConfigDao = new TestMemoryDao<>();
        TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
        TestMemoryDao<MetadataView> viewDao = new TestMemoryDao<>();
        TestMemoryDao<MetadataViewField> viewFieldDao = new TestMemoryDao<>();
        TestMemoryDao<PlatformModuleAction> actionDao = new TestMemoryDao<>();
        TestMemoryDao<ModuleMetadataFormulaRule> formulaRuleDao = new TestMemoryDao<>();
        TestMemoryDao<MenuScheme> schemeDao = new TestMemoryDao<>();
        TestMemoryDao<Menu> menuDao = new TestMemoryDao<>();
        TestMemoryDao<DictionaryCategory> categoryDao = new TestMemoryDao<>();
        TestMemoryDao<DictionaryItem> itemDao = new TestMemoryDao<>();
        ApplicationService applicationService = new ApplicationService(applicationDao);
        DictionaryCategoryService categoryService = new DictionaryCategoryService(categoryDao);
        DictionaryItemService itemService = new DictionaryItemService(itemDao, categoryService);
        PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
        MetadataService metadataService = new MetadataService(metadataDao);
        FieldSpecService fieldTypeService = new FieldSpecService(fieldTypeDao);
        fieldTypeService.insert(fieldType("string", FieldType.STRING, 128));
        fieldTypeService.insert(fieldType("id", FieldType.STRING, 32));
        fieldTypeService.insert(fieldType("integer", FieldType.INTEGER, null));
        MetadataFieldService fieldService = new MetadataFieldService(fieldDao, metadataService, fieldTypeService);
        ModuleMetadataRelationService relationService =
                new ModuleMetadataRelationService(relationDao, moduleService, metadataService);
        MetadataFieldConfigService fieldConfigService =
                new MetadataFieldConfigService(fieldConfigDao, fieldService, metadataService, fieldTypeService,
                        categoryService, relationService);
        MetadataFieldDefinitionCompiler fieldDefinitionCompiler =
                new MetadataFieldDefinitionCompiler(fieldTypeService, fieldConfigService);
        MetadataFieldReferenceConfigService referenceConfigService =
                new MetadataFieldReferenceConfigService(referenceConfigDao, fieldService, metadataService,
                        fieldTypeService, moduleService, relationService);
        MetadataViewService viewService = new MetadataViewService(viewDao, relationService);
        MetadataViewFieldService viewFieldService =
                new MetadataViewFieldService(viewFieldDao, viewService, fieldService, relationService);
        PlatformModuleActionService actionService = new PlatformModuleActionService(actionDao, moduleService);
        ModuleMetadataFormulaRuleService formulaRuleService =
                new ModuleMetadataFormulaRuleService(formulaRuleDao, relationService, fieldService);
        MenuSchemeService schemeService = new MenuSchemeService(schemeDao);
        MenuService menuService = new MenuService(menuDao, schemeService, moduleService);
        return new PlatformServices(applicationService, moduleService, metadataService, fieldService, fieldConfigService,
                referenceConfigService, fieldDefinitionCompiler, relationService, schemeService, menuService,
                categoryService, itemService, viewService, viewFieldService, actionService, formulaRuleService);
    }

    private Application application(String alias) {
        Application application = new Application();
        application.setAlias(alias);
        application.setTitle(alias);
        return application;
    }

    private PlatformModule module(String alias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle(alias);
        return module;
    }

    private Metadata metadata(String applicationAlias, String alias, String tableName) {
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(alias);
        metadata.setTitle(alias);
        metadata.setTableName(tableName);
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
        return type;
    }

    private MetadataFieldConfig fieldConfig(String fieldId) {
        MetadataFieldConfig config = new MetadataFieldConfig();
        config.setMetadataFieldId(fieldId);
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

    private MetadataFieldReferenceConfig referenceConfig(String fieldId, String targetMetadataId) {
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(fieldId);
        config.setTargetMetadataId(targetMetadataId);
        return config;
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
        relation.setRelationAlias("contacts");
        relation.setForeignKey("customerId");
        relation.setAutoPopulate(true);
        relation.setTitle("contacts");
        return relation;
    }

    private MenuScheme menuScheme(String alias) {
        MenuScheme scheme = new MenuScheme();
        scheme.setAlias(alias);
        scheme.setScopeType(MenuScopeType.TENANT);
        scheme.setTenantId("tenant-a");
        scheme.setTitle(alias);
        return scheme;
    }

    private Menu moduleMenu(String schemeId, String title, String moduleAlias) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias(moduleAlias);
        menu.setTitle(title);
        return menu;
    }

    private DictionaryCategory category(String applicationAlias, String alias, DictionaryCategoryKind kind) {
        DictionaryCategory category = new DictionaryCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setCategoryKind(kind);
        category.setTitle(alias);
        return category;
    }

    private DictionaryItem item(String categoryId, String categoryAlias, String code) {
        DictionaryItem item = new DictionaryItem();
        item.setCategoryId(categoryId);
        item.setCategoryAlias(categoryAlias);
        item.setCode(code);
        item.setTitle(code);
        return item;
    }

    private record PlatformServices(ApplicationService applicationService,
                                    PlatformModuleService moduleService,
                                    MetadataService metadataService,
                                    MetadataFieldService fieldService,
                                    MetadataFieldConfigService fieldConfigService,
                                    MetadataFieldReferenceConfigService referenceConfigService,
                                    MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                    ModuleMetadataRelationService relationService,
                                    MenuSchemeService schemeService,
                                    MenuService menuService,
                                    DictionaryCategoryService categoryService,
                                    DictionaryItemService itemService,
                                    MetadataViewService viewService,
                                    MetadataViewFieldService viewFieldService,
                                    PlatformModuleActionService actionService,
                                    ModuleMetadataFormulaRuleService formulaRuleService) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        DynamicSchemaService dynamicSchemaService(IDatabaseOperations<?> operations) {
            return new DynamicSchemaService(operations);
        }
    }
}
