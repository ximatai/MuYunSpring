package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.demo.DemoBootstrapTask;
import net.ximatai.muyun.spring.demo.ExamDemoBootstrapTask;
import net.ximatai.muyun.spring.demo.SchoolDemoMountTask;
import net.ximatai.muyun.spring.demo.ExamPostponeActionExecutor;
import net.ximatai.muyun.spring.demo.ExamPageDemoBootstrapTask;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMember;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMemberService;
import net.ximatai.muyun.spring.demo.school.classroom.Classroom;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoConfiguration;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoMenuGroups;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategory;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategoryService;
import net.ximatai.muyun.spring.demo.school.student.Student;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.teacher.Teacher;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RestoreEntryResult;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlan;
import net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService;
import net.ximatai.muyun.spring.platform.web.ResolvedReferenceDisplayProjectionDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 教学管理动静一体演示：在真实 Boot 上下文中验证 Spring 装配、Repository 持久化、
 * 自动建表、模块/端点注册和业务 Ability 组合。
 */
@Testcontainers
@SpringBootTest(classes = MuYunSpringApplication.class, properties = "muyun.i18n.enabled-languages=zh-CN,en,fr")
@ContextConfiguration(classes = TeachingDemoConfiguration.class)
@ActiveProfiles("school-demo")
public class TeachingDemoIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StudentService students;

    @Autowired
    private SubjectCategoryService subjects;

    @Autowired
    private TeacherService teachers;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ClassMemberService members;

    @Autowired
    private MenuService menus;

    @Autowired
    private ClassroomService classrooms;

    @Autowired
    private RecycleBinFacade recycleBin;

    @Autowired
    private RegisteredWebEndpointCatalog endpointCatalog;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformModuleActionService moduleActions;

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private MetadataFieldService metadataFields;

    @Autowired
    private MetadataFieldReferenceConfigService referenceConfigs;

    @Autowired
    private MetadataFieldConfigService fieldConfigs;

    @Autowired
    private ModuleMetadataRelationService metadataRelations;

    @Autowired
    private DynamicRecordService dynamicRecords;

    @Autowired
    private PlatformPageDefinitionService pageDefinitions;

    @Autowired
    private PlatformPresentationVariantService presentationVariants;

    @Autowired
    private PlatformPresentationRevisionService presentationRevisions;

    @Autowired
    private PlatformPresentationRevisionPublishService presentationRevisionPublisher;

    @Autowired
    private ExamPageDemoBootstrapTask examPageBootstrap;

    @Autowired
    private PlatformModuleRuntimeContextService runtimeContexts;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Autowired
    private net.ximatai.muyun.spring.iam.tenant.TenantService tenantService;
    @Autowired
    private net.ximatai.muyun.spring.web.RequestTenantVerifier requestTenantVerifier;
    @Autowired
    private net.ximatai.muyun.spring.iam.tenant.TenantApplicationService tenantApplications;
    @Autowired
    private net.ximatai.muyun.spring.iam.organization.OrganizationService organizations;
    @Autowired
    private net.ximatai.muyun.spring.iam.department.DepartmentService departments;
    @Autowired
    private net.ximatai.muyun.spring.iam.employee.EmployeeService employees;

    @Test
    void actualDynamicStandardActionsKeepCodeTitlesThroughRegistrationCompilationAndWebProjection() throws Exception {
        var moduleAlias = ExamDemoBootstrapTask.MODULE_ALIAS;
        var actions = webApplicationContext.getBean(PlatformModuleActionService.class);
        var contexts = webApplicationContext.getBean(PlatformModuleRuntimeContextService.class);
        var records = webApplicationContext.getBean(DynamicRecordService.class);
        var catalog = webApplicationContext.getBean(net.ximatai.muyun.spring.common.i18n.LanguageCatalog.class);
        var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        var mapper = webApplicationContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("dynamic-action-i18n", "Dynamic Actions"));
             var system = TenantContext.system("dynamic action producer contract")) {
            var persisted = actions.findByModuleAliasAndActionCode(moduleAlias, "update");
            assertThat(persisted.getSystemManaged()).isTrue();
            assertThat(persisted.getSourceType()).isEqualTo(net.ximatai.muyun.spring.platform.module.ModuleActionSourceType.DYNAMIC_MODULE);
            var compiled = records.describe(moduleAlias).actions().stream().filter(action -> action.code().equals("update")).findFirst().orElseThrow();
            assertThat(compiled.titleSource()).isEqualTo(persisted.titleSource());
            var context = contexts.context(moduleAlias);
            var runtime = context.actions().stream().filter(action -> action.actionCode().equals("update")).findFirst().orElseThrow();
            assertThat(runtime.titleSource()).isEqualTo(compiled.titleSource());
            assertThat(runtime.titleSource().sourceKind()).isEqualTo(net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE);
            assertThat(runtime.titleSource().sourceId()).isEqualTo(PlatformAction.UPDATE.titleKey());
            assertThat(resources.resolve(runtime.titleSource(), java.util.Map.of(), "en").text()).isEqualTo("Edit");
            MockMvc mvc = webAppContextSetup(webApplicationContext).addFilters(
                    webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
            var response = mvc.perform(get("/platform.module/" + moduleAlias + "/context").header("Accept-Language", "en")).andReturn();
            assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
            var output = mapper.readTree(response.getResponse().getContentAsString());
            var update = java.util.stream.StreamSupport.stream(output.path("actions").spliterator(), false)
                    .filter(action -> action.path("actionCode").asText().equals("update")).findFirst().orElseThrow();
            assertThat(update.path("title").asText()).isEqualTo("Edit");
            assertThat(update.at("/titleSource/sourceKind").asText()).isEqualTo("CODE");
            assertThat(persisted.getTitle()).isEqualTo(PlatformAction.UPDATE.title());
        }
    }

    @Test
    void deployedEnglishResourcesCoverTheActualStaticModuleAndEnumSourceDirectory() {
        var catalog = webApplicationContext.getBean(net.ximatai.muyun.spring.common.i18n.LanguageCatalog.class);
        var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        var plans = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog.class);
        int sources = 0;
        for (var plan : plans.plans().values()) if (plan.uiDescriptor() != null)
            for (var source : plan.uiDescriptor().sourceTexts().values()) {
                if (source.sourceKind() != net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE) continue;
                var english = resources.resolve(source, java.util.Map.of(), "en");
                assertThat(english.missing()).as(source.sourceId()).isFalse();
                assertThat(english.text()).as(source.sourceId()).doesNotContainPattern("[\\p{IsHan}]");
                sources++;
            }
        var declarations = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog.class);
        for (var module : declarations.definitions()) for (var action : module.actions()) {
            var source = action.titleSource(module.moduleAlias());
            if (source == null) continue;
            var english = resources.resolve(source, java.util.Map.of(), "en");
            assertThat(english.missing()).as(source.sourceId()).isFalse();
            assertThat(english.text()).as(source.sourceId()).doesNotContainPattern("[\\p{IsHan}]");
        }
        assertThat(sources).isGreaterThan(500);
    }

    @Test
    void storedMenuDictionaryAndNativeColumnTranslationsRemainIndependentOfOriginalDataAndPlans() throws Exception {
        var translations = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.localization.ConfigurationTranslationService.class);
        var dictionaries = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class);
        var plans = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog.class);
        var resolver = webApplicationContext.getBean(net.ximatai.muyun.spring.common.i18n.TextResolver.class);
        var mapper = webApplicationContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        MockMvc mvc = webAppContextSetup(webApplicationContext).addFilters(
                webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("platform-i18n", "Translation Test"));
             var system = TenantContext.system("configuration text contract")) {
            var menu = menus.select("platform.menu.module.education.teacher");
            String originalMenuTitle = menu.getTitle();
            var menuSource = net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources.menuTitle(menu.getId(), originalMenuTitle);
            assertThat(translations.currentSources(MenuService.MODULE_ALIAS)).contains(menuSource);
            publishTestTranslation(translations, MenuService.MODULE_ALIAS, menuSource, "Teaching staff");
            var navigation = mapper.readTree(mvc.perform(get("/platform.menu/mine").header("Accept-Language", "en"))
                    .andReturn().getResponse().getContentAsString());
            assertThat(navigation.findValuesAsText("title")).contains("Teaching staff");
            assertThat(menus.select(menu.getId()).getTitle()).isEqualTo(originalMenuTitle);

            net.ximatai.muyun.spring.platform.dictionary.DictionaryItem item;
            try (var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                item = dictionaries.resolveItem("education", "exam_attendance_status", "ATTENDED");
            }
            var dictionarySource = net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle());
            publishTestTranslation(translations, net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.MODULE_ALIAS, dictionarySource, "Attended");
            com.fasterxml.jackson.databind.JsonNode options;
            try (var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                assertThat(webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.ModuleFieldOptionService.class)
                        .options("education.exam", "exam_participant", "attendanceStatus", true, null)).isNotEmpty();
                var response = mvc.perform(get("/platform.module/education.exam/fields/attendanceStatus/options")
                        .param("entityAlias", "exam_participant").header("Accept-Language", "en")).andReturn().getResponse();
                assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
                options = mapper.readTree(response.getContentAsString());
            }
            boolean translated = false;
            for (var option : options) if (option.path("code").asText().equals("ATTENDED")) {
                assertThat(option.at("/titleText/source/sourceId").asText()).as(option.toString()).isEqualTo(dictionarySource.sourceId());
                assertThat(option.path("title").asText()).as(option.toString()).isEqualTo("Attended");
                translated = true;
            }
            assertThat(translated).isTrue();
            try (var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                assertThat(dictionaries.select(item.getId()).getTitle()).isEqualTo(item.getTitle());
            }

            var descriptor = dynamicRecords.describe("education.exam");
            var main = descriptor.entities().stream().filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias())).findFirst().orElseThrow();
            var field = main.fields().stream().filter(candidate -> candidate.fieldName().equals("title")).findFirst().orElseThrow();
            var nativeSource = net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources.metadataFieldTitle(
                    descriptor.moduleAlias(), main.entityAlias(), field.fieldName(), field.title());
            assertThat(translations.currentSources(descriptor.moduleAlias())).contains(nativeSource);
            var installed = plans.find(descriptor.moduleAlias()).orElseThrow();
            publishTestTranslation(translations, descriptor.moduleAlias(), nativeSource, "Exam name");
            try (var language = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("en")) {
                assertThat(resolver.resolve(nativeSource, java.util.Map.of()).text()).isEqualTo("Exam name");
                var original = new net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder().build(descriptor);
                var localized = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPresentation.class).project(original, descriptor);
                assertThat(localized.sheets().getFirst().columns().stream().filter(column -> column.fieldName().equals("title")))
                        .singleElement().satisfies(column -> assertThat(column.title()).isEqualTo("Exam name"));
                assertThat(localized.sheets().getFirst().sheetName()).isEqualTo(original.sheets().getFirst().sheetName());
            }
            assertThat(plans.find(descriptor.moduleAlias()).orElseThrow()).isSameAs(installed);
            assertThat(dynamicRecords.describe(descriptor.moduleAlias()).entities().stream().filter(entity -> entity.entityAlias().equals(main.entityAlias()))
                    .flatMap(entity -> entity.fields().stream()).filter(candidate -> candidate.fieldName().equals("title")))
                    .singleElement().satisfies(candidate -> assertThat(candidate.title()).isEqualTo(field.title()));
        }
    }

    private void publishTestTranslation(net.ximatai.muyun.spring.platform.localization.ConfigurationTranslationService translations,
            String moduleAlias, net.ximatai.muyun.spring.common.i18n.TextReference source, String english) {
        var existing = translations.translations(moduleAlias).stream().filter(row -> row.getSourceId().equals(source.sourceId()) &&
                row.getSourceVersion().equals(source.sourceVersion()) && row.getLanguageTag().equals("en")).findFirst().orElse(null);
        var draft = translations.saveDraft(moduleAlias, source.sourceId(), source.sourceVersion(), "en", english, existing == null ? null : existing.getVersion());
        translations.publish(draft.getId(), source.sourceVersion(), draft.getVersion());
    }

    @Test
    void configuredExamTranslationsPublishIndependentlyAndKeepMainChildRecordValues() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var translations = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.localization.ConfigurationTranslationService.class);
        var plans = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog.class);
        MockMvc mvc = webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("exam-i18n", "Language Test"));
             var system = TenantContext.system("exam translation contract")) {
            examPageBootstrap.run();
            plans.rebuildDynamicPlan("education.exam");
            var before = plans.find("education.exam").orElseThrow();
            var originals = translations.currentSources("education.exam");
            var main = originals.stream().filter(source -> source.sourceId().endsWith("/page/detail/editor/fields/main/title/label")).findFirst().orElseThrow();
            var child = originals.stream().filter(source -> (source.sourceId().endsWith("/score/label") || source.sourceId().endsWith("/score/title"))).findFirst().orElseThrow();
            for (var entry : java.util.Map.of(main, "Exam name", child, "Score").entrySet()) {
                var draft = translations.saveDraft("education.exam", entry.getKey().sourceId(), entry.getKey().sourceVersion(), "en", entry.getValue(), null);
                var stillOriginal = mapper.readTree(mvc.perform(get("/platform.module/education.exam/context").header("Accept-Language", "en")).andReturn().getResponse().getContentAsString());
                assertThat(stillOriginal.toString()).doesNotContain("\"text\":\"" + entry.getValue() + "\"");
                translations.publish(draft.getId(), entry.getKey().sourceVersion(), draft.getVersion());
            }
            var english = mapper.readTree(mvc.perform(get("/platform.module/education.exam/context").header("Accept-Language", "en")).andReturn().getResponse().getContentAsString());
            var chinese = mapper.readTree(mvc.perform(get("/platform.module/education.exam/context").header("Accept-Language", "zh-CN")).andReturn().getResponse().getContentAsString());
            var bindings = english.path("textBindings").fields();
            boolean childLabelFound = false;
            while (bindings.hasNext()) {
                var entry = bindings.next();
                if (entry.getValue().at("/source/sourceId").asText().equals(child.sourceId())) {
                    assertThat(english.at(entry.getKey()).asText()).isEqualTo("Score");
                    assertThat(chinese.at(entry.getKey()).asText()).isEqualTo(child.originalText());
                    childLabelFound = true;
                }
            }
            assertThat(childLabelFound).isTrue();
            assertThat(english.at("/uiDescriptor/page/detail/editor/fields/0/label").asText()).isEqualTo("Exam name");
            assertThat(plans.find("education.exam").orElseThrow()).isSameAs(before);
            try (var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                String title = "中文考试原名-" + serial();
                var inserted = mvc.perform(post("/education.exam/insert").header("Accept-Language", "en")
                        .contentType("application/json").content("""
                        {"values":{"title":"%s","classroomId":"demo_classroom_g1a",
                        "subjectCategoryId":"demo_subject_mathematics","examDate":"2026-09-07"},
                        "children":{"participants":[{"values":{"studentId":"demo_student_1001","score":81,"attendanceStatus":"ATTENDED"}}]}}
                        """.formatted(title))).andReturn().getResponse();
                assertThat(inserted.getStatus()).as(inserted.getContentAsString()).isEqualTo(201);
                String id = mapper.readTree(inserted.getContentAsString()).at("/data/id").asText();
                var reopened = mvc.perform(get("/education.exam/view/{id}", id).header("Accept-Language", "en")).andReturn().getResponse();
                assertThat(reopened.getStatus()).as(reopened.getContentAsString()).isEqualTo(200);
                assertThat(reopened.getContentAsString()).contains(title, "ATTENDED");
                var queried = mvc.perform(post("/education.exam/query").header("Accept-Language", "en")
                        .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("quickSearch", title)))).andReturn().getResponse();
                assertThat(queried.getStatus()).as(queried.getContentAsString()).isEqualTo(200);
                assertThat(queried.getContentAsString()).contains(title);
                var saved = dynamicRecords.mainEntity("education.exam").select(id);
                var updated = mvc.perform(post("/education.exam/update/{id}", id).header("Accept-Language", "en")
                        .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("id", id, "version", saved.getVersion(),
                                "values", java.util.Map.of("title", title, "classroomId", "demo_classroom_g1a",
                                        "subjectCategoryId", "demo_subject_mathematics", "examDate", "2026-09-07"))))).andReturn().getResponse();
                assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);

                assertThat(dynamicRecords.mainEntity("education.exam").select(id).getValues()).containsEntry("title", title);
                assertThat(dynamicRecords.listSystem("education.exam", "exam_participant", Criteria.of().eq("examId", id)))
                        .singleElement().satisfies(record -> assertThat(record.getValues()).containsEntry("attendanceStatus", "ATTENDED"));
            }
        }
        MockMvc anonymous = webAppContextSetup(webApplicationContext).addFilters(
                new net.ximatai.muyun.spring.web.CurrentUserWebFilter(() -> java.util.Optional.empty(), requestTenantVerifier)).build();
        assertThat(anonymous.perform(get("/platform.localization/catalog")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(anonymous.perform(get("/platform.localization/modules/education.exam/sources")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void thirdLanguageUsesDeployedResourcesForRealTeacherValidationSaveAndReopen() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var mvc = webAppContextSetup(webApplicationContext).addFilters(webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("fr-teacher", "用户原名"));
             var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            var response = mvc.perform(get("/platform.module/education.teacher/context").header("Accept-Language", "fr-CA")).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
            assertThat(response.getHeader("Content-Language")).isEqualTo("fr");
            var descriptor = mapper.readTree(response.getContentAsString());
            assertThat(descriptor.at("/uiDescriptor/page/explorer/title").asText()).isEqualTo("Liste des enseignants");
            assertThat(descriptor.at("/uiDescriptor/page/detail/editor/fields/1/label").asText()).isEqualTo("Nom de l’enseignant");
            String suffix = serial();
            String subjectId = subjects.insert(subject("FR-" + suffix, "中文学科原值", null));
            var invalid = mvc.perform(post("/education.teacher/insert").header("Accept-Language", "fr")
                    .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("title", "不能保存", "subjectCategoryId", subjectId)))).andReturn().getResponse();
            assertThat(invalid.getStatus()).isEqualTo(400);
            assertThat(mapper.readTree(invalid.getContentAsString()).path("message").asText()).isEqualTo("Renseignez le champ obligatoire et réessayez");
            var inserted = mvc.perform(post("/education.teacher/insert").header("Accept-Language", "fr")
                    .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("teacherNo", "FR-" + suffix, "title", "用户中文姓名", "subjectCategoryId", subjectId)))).andReturn().getResponse();
            assertThat(inserted.getStatus()).as(inserted.getContentAsString()).isEqualTo(201);
            var created = mapper.readTree(inserted.getContentAsString());
            assertThat(created.at("/message/text").asText()).contains("créé avec succès");
            var reopened = mvc.perform(get("/education.teacher/view/{id}", created.at("/data/id").asText()).header("Accept-Language", "fr")).andReturn().getResponse();
            assertThat(mapper.readTree(reopened.getContentAsString()).path("title").asText()).isEqualTo("用户中文姓名");
            assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isNull();
        }
    }

    @Test
    void concurrentRequestLanguagesDoNotMutateTheSharedStaticPlanOrLeakAcrossWorkerReuse() throws Exception {
        var mvc = webAppContextSetup(webApplicationContext).addFilters(webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
        var plans = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog.class);
        var original = plans.find("education.teacher").orElseThrow();
        var tags = List.of("zh-CN", "en", "fr");
        var titles = List.of("教师列表", "Teachers", "Liste des enseignants");
        var barrier = new java.util.concurrent.CyclicBarrier(3);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
            for (int lane = 0; lane < tags.size(); lane++) {
                final int index = lane;
                futures.add(executor.submit(() -> {
                    try (var user = CurrentUserContext.use(CurrentUser.systemUser("language-user-" + index, "原名" + index));
                         var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                        barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        for (int repeat = 0; repeat < 4; repeat++) {
                            var response = mvc.perform(get("/platform.module/education.teacher/context").header("Accept-Language", tags.get(index))).andReturn().getResponse();
                            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
                            assertThat(response.getHeader("Content-Language")).isEqualTo(tags.get(index));
                            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).at("/uiDescriptor/page/explorer/title").asText()).isEqualTo(titles.get(index));
                            assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("language-user-" + index);
                            assertThat(TenantContext.currentTenantId()).contains(DemoBootstrapTask.TENANT_ALIAS);
                            assertThat(net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).isNull();
                        }
                    }
                    assertThat(CurrentUserContext.currentUser()).isEmpty();
                    assertThat(TenantContext.currentTenantId()).isEmpty();
                    return null;
                }));
            }
            for (var future : futures) future.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(executor.submit(() -> net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag()).get()).isNull();
        }
        assertThat(plans.find("education.teacher").orElseThrow()).isSameAs(original);
    }

    @Test
    void staticTeacherSupportsBilingualPresentationAndRealCrudWithoutTranslatingRecordValues() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var languageFilter = webApplicationContext.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class);
        MockMvc mvc = webAppContextSetup(webApplicationContext).addFilters(languageFilter).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("teacher-i18n", "Language Test"));
             var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            String suffix = serial();
            String subjectId = subjects.insert(subject("L-" + suffix, "中文学科原值", null));
            var english = mvc.perform(get("/platform.module/education.teacher/context")
                    .header("Accept-Language", "en")).andReturn().getResponse();
            assertThat(english.getStatus()).as(english.getContentAsString()).isEqualTo(200);
            var descriptor = mapper.readTree(english.getContentAsString());
            assertThat(descriptor.at("/uiDescriptor/page/explorer/title").asText()).isEqualTo("Teachers");
            assertThat(descriptor.at("/uiDescriptor/page/detail/editor/fields/1/label").asText()).isEqualTo("Teacher name");
            var chinese = mvc.perform(get("/platform.module/education.teacher/context")
                    .header("Accept-Language", "zh-CN")).andReturn().getResponse();
            assertThat(mapper.readTree(chinese.getContentAsString()).at("/uiDescriptor/page/explorer/title").asText())
                    .isEqualTo("教师列表");
            var invalid = mvc.perform(post("/education.teacher/insert").header("Accept-Language", "en")
                    .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of(
                            "title", "不可保存", "subjectCategoryId", subjectId))))
                    .andReturn().getResponse();
            assertThat(invalid.getStatus()).as(invalid.getContentAsString()).isEqualTo(400);
            var error = mapper.readTree(invalid.getContentAsString());
            assertThat(error.path("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.path("message").asText()).isEqualTo("Complete the required field and try again");
            assertThat(error.at("/targets/0/fieldName").asText()).isEqualTo("teacher_no");
            var insert = mvc.perform(post("/education.teacher/insert").header("Accept-Language", "en")
                    .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of(
                            "teacherNo", "L-" + suffix, "title", "中文教师原名", "subjectCategoryId", subjectId))))
                    .andReturn().getResponse();
            assertThat(insert.getStatus()).as(insert.getContentAsString()).isEqualTo(201);
            var created = mapper.readTree(insert.getContentAsString());
            assertThat(created.at("/message/code").asText()).isEqualTo("platform.crud.created");
            assertThat(created.at("/message/text").asText()).contains("created successfully");
            String id = created.at("/data/id").asText();
            assertThat(teachers.select(id).getTitle()).isEqualTo("中文教师原名");
            var query = mvc.perform(post("/education.teacher/query").header("Accept-Language", "en")
                    .contentType("application/json").content("{\"quickSearch\":\"中文教师原名\"}"))
                    .andReturn().getResponse();
            assertThat(query.getStatus()).as(query.getContentAsString()).isEqualTo(200);
            assertThat(query.getContentAsString()).contains("中文教师原名");
            var values = (com.fasterxml.jackson.databind.node.ObjectNode) created.path("data");
            values.put("title", "修改后的中文原名");
            var update = mvc.perform(post("/education.teacher/update/{id}", id).header("Accept-Language", "zh-CN")
                    .contentType("application/json").content(mapper.writeValueAsString(values)))
                    .andReturn().getResponse();
            assertThat(update.getStatus()).as(update.getContentAsString()).isEqualTo(200);
            assertThat(mapper.readTree(update.getContentAsString()).at("/message/text").asText()).contains("修改成功");
            var reopen = mvc.perform(get("/education.teacher/view/{id}", id).header("Accept-Language", "en"))
                    .andReturn().getResponse();
            assertThat(reopen.getStatus()).as(reopen.getContentAsString()).isEqualTo(200);
            assertThat(mapper.readTree(reopen.getContentAsString()).path("title").asText()).isEqualTo("修改后的中文原名");
        }
    }

    @Test
    void shouldCreateApplicationThenModuleThroughStandardFormsWithoutConstructionPlan() throws Exception {
        MockMvc mvc = webAppContextSetup(webApplicationContext).build();
        String app = "guided_application_long_identifier_" + serial();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("guided-admin", "配置管理员"));
             var scope = TenantContext.system("standard creation contract")) {
            var application = mvc.perform(post("/platform.application/insert")
                    .header("X-Muyun-Save-Request", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content("{\"alias\":\"" + app + "\",\"title\":\"引导创建应用\"}")).andReturn().getResponse();
            assertThat(application.getStatus()).as(application.getContentAsString()).isEqualTo(201);
            String requestId = UUID.randomUUID().toString();
            String body = "{\"alias\":\"" + app + ".entry\",\"applicationAlias\":\"" + app
                    + "\",\"title\":\"登记\",\"moduleKind\":\"dynamic\",\"entryType\":\"module\",\"description\":\"记录日常登记事项\"}";
            for (int retry = 0; retry < 2; retry++) {
                var module = mvc.perform(post("/platform.module/insert")
                        .header("X-MuYun-Page-Context", "{\"application\":\"" + app + "\"}")
                        .header("X-Muyun-Save-Request", requestId).contentType("application/json")
                        .content(body)).andReturn().getResponse();
                assertThat(module.getStatus()).as(module.getContentAsString()).isEqualTo(201);
            }
            var stored = webApplicationContext.getBean(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class)
                    .select(app + ".entry");
            assertThat(stored.getApplicationAlias()).isEqualTo(app);
            assertThat(stored.getDescription()).isEqualTo("记录日常登记事项");
            var missingScope = mvc.perform(get("/platform.module/save-receipts/{requestId}", requestId)).andReturn().getResponse();
            assertThat(missingScope.getStatus()).isBetween(400, 499);
            var recovered = mvc.perform(get("/platform.module/save-receipts/{requestId}", requestId)
                    .header("X-MuYun-Page-Context", "{\"application\":\"" + app + "\"}"))
                    .andReturn().getResponse();
            assertThat(recovered.getStatus()).as(recovered.getContentAsString()).isEqualTo(200);
            assertThat(recovered.getContentAsString()).contains("\"committed\":true", app + ".entry");
        }
    }

    @Test
    void shouldKeepStaticAndDynamicHttpSaveReceiptsWithoutDuplicatingRecords() throws Exception {
        MockMvc mvc = webAppContextSetup(webApplicationContext).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("save-receipt-acceptance", "Save Acceptance"));
             var tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            for (String module : List.of("education.student", "education.exam")) {
                String requestId = UUID.randomUUID().toString();
                String title = "Save receipt " + serial();
                String body = module.endsWith("student")
                        ? "{\"studentNo\":\"SR-" + serial() + "\",\"title\":\"" + title + "\",\"grade\":\"一年级\"}"
                        : "{\"values\":{\"title\":\"" + title + "\",\"classroomId\":\"demo_classroom_g1a\","
                            + "\"subjectCategoryId\":\"demo_subject_mathematics\",\"examDate\":\"2026-09-07\"}}";
                for (int retry = 0; retry < 2; retry++) {
                    var saved = mvc.perform(post("/" + module + "/insert")
                            .header("X-Muyun-Save-Request", requestId).contentType("application/json").content(body)).andReturn();
                    assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(201);
                }
                String recordId;
                if (module.endsWith("student")) {
                    var records = students.list(Criteria.of().eq("title", title), PageRequest.of(1, 10));
                    assertThat(records).hasSize(1);
                    recordId = records.getFirst().getId();
                } else {
                    var records = dynamicRecords.mainEntity(module).list(Criteria.of().eq("title", title), PageRequest.of(1, 10));
                    assertThat(records).hasSize(1);
                    recordId = records.getFirst().getId();
                }
                var receipt = mvc.perform(get("/" + module + "/save-receipts/{requestId}", requestId)).andReturn();
                assertThat(receipt.getResponse().getStatus()).as(receipt.getResponse().getContentAsString()).isEqualTo(200);
                assertThat(receipt.getResponse().getContentAsString()).contains("\"committed\":true", recordId);
                var changed = mvc.perform(post("/" + module + "/insert").header("X-Muyun-Save-Request", requestId)
                        .contentType("application/json").content(body.replace(title, title + " changed"))).andReturn();
                assertThat(changed.getResponse().getStatus()).isGreaterThanOrEqualTo(400).isLessThan(500);
            }
        }
    }

    @Test
    void shouldIsolateStaticAndDynamicBusinessRequestsUsingVerifiedTenantHeader() throws Exception {
        String otherTenant = "preview_" + serial();
        try (TenantContext.Scope ignored = TenantContext.system("preview fixture")) {
            var tenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            tenant.setAlias(otherTenant);
            tenant.setTitle("Preview tenant");
            tenant.setEnabled(true);
            tenantService.insert(tenant);
        }
        var identity = new java.util.concurrent.atomic.AtomicReference<>(CurrentUser.systemUser("preview-admin", "admin"));
        MockMvc mvc = webAppContextSetup(webApplicationContext)
                .addFilters(new net.ximatai.muyun.spring.web.CurrentUserWebFilter(
                        () -> java.util.Optional.of(identity.get()), requestTenantVerifier)).build();
        String header = net.ximatai.muyun.spring.web.CurrentUserWebFilter.TENANT_HEADER;
        String title = "Preview HTTP " + serial();
        MvcResult created = mvc.perform(post("/education.exam/insert").header(header, DemoBootstrapTask.TENANT_ALIAS)
                .contentType("application/json").content("""
                {"values":{"title":"%s","classroomId":"demo_classroom_g1a",
                "subjectCategoryId":"demo_subject_mathematics","examDate":"2026-09-07"},
                "children":{"participants":[{"values":{"studentId":"demo_student_1001",
                "score":81,"attendanceStatus":"ATTENDED"}}]}}
                """.formatted(title))).andReturn();
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        String recordId;
        Integer recordVersion;
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("preview-fixture", "fixture"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            var saved = dynamicRecords.mainEntity(ExamDemoBootstrapTask.MODULE_ALIAS)
                    .list(Criteria.of().eq("title", title), PageRequest.of(1, 10)).getFirst();
            recordId = saved.getId();
            recordVersion = saved.getVersion();
            assertThat(saved.getTenantId()).isEqualTo(DemoBootstrapTask.TENANT_ALIAS);
            assertThat(dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam_participant",
                    Criteria.of().eq("examId", recordId))).singleElement()
                    .satisfies(child -> assertThat(child.getTenantId()).isEqualTo(DemoBootstrapTask.TENANT_ALIAS));
        }
        var visible = mvc.perform(get("/education.exam/view/{id}", recordId)
                .header(header, DemoBootstrapTask.TENANT_ALIAS)).andReturn();
        assertThat(visible.getResponse().getStatus()).isEqualTo(200);
        assertThat(visible.getResponse().getContentAsString()).contains(title);
        var hidden = mvc.perform(get("/education.exam/view/{id}", recordId).header(header, otherTenant)).andReturn();
        assertThat(hidden.getResponse().getContentAsString()).doesNotContain(title);
        var query = mvc.perform(post("/education.exam/query").header(header, otherTenant)
                .contentType("application/json").content("{}")).andReturn();
        assertThat(query.getResponse().getStatus()).isEqualTo(200);
        assertThat(query.getResponse().getContentAsString()).doesNotContain(title);
        var denied = mvc.perform(post("/education.exam/update/{id}", recordId).header(header, otherTenant)
                .contentType("application/json").content("{\"values\":{\"title\":\"forged\"}}" )).andReturn();
        assertThat(denied.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        var deniedDelete = mvc.perform(post("/education.exam/delete/{id}", recordId).header(header, otherTenant)
                .contentType("application/json").content("{\"version\":%d}".formatted(recordVersion))).andReturn();
        assertThat(deniedDelete.getResponse().getStatus()).isEqualTo(200);
        var deletedCount = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(deniedDelete.getResponse().getContentAsString());
        if (deletedCount.isObject()) deletedCount = deletedCount.required("data");
        assertThat(deletedCount.isIntegralNumber()).isTrue();
        assertThat(deletedCount.intValue()).isZero();
        var references = mvc.perform(post("/education.exam/references/classroomId/resolve")
                .header(header, DemoBootstrapTask.TENANT_ALIAS).contentType("application/json").content("{}"))
                .andReturn();
        assertThat(references.getResponse().getStatus()).as(references.getResponse().getContentAsString()).isEqualTo(200);
        var changed = mvc.perform(post("/education.exam/update/{id}", recordId)
                .header(header, DemoBootstrapTask.TENANT_ALIAS).contentType("application/json").content("""
                {"version":%d,"values":{"title":"%s-updated","classroomId":"demo_classroom_g1a",
                 "subjectCategoryId":"demo_subject_mathematics","examDate":"2026-09-07"}}
                """.formatted(recordVersion, title))).andReturn();
        assertThat(changed.getResponse().getStatus()).as(changed.getResponse().getContentAsString()).isEqualTo(200);
        String studentId;
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("preview-fixture", "fixture"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            studentId = students.insert(student("P-" + serial(), title, "一年级"));
        }
        var staticVisible = mvc.perform(get("/education.student/view/{id}", studentId)
                .header(header, DemoBootstrapTask.TENANT_ALIAS)).andReturn();
        assertThat(staticVisible.getResponse().getContentAsString()).contains(title);
        var staticHidden = mvc.perform(get("/education.student/view/{id}", studentId).header(header, otherTenant)).andReturn();
        assertThat(staticHidden.getResponse().getContentAsString()).doesNotContain(title);
        String forgedNumber = "F-" + serial();
        mvc.perform(post("/education.student/insert").header(header, DemoBootstrapTask.TENANT_ALIAS)
                .contentType("application/json").content("""
                {"studentNo":"%s","title":"Forged tenant","grade":"一年级","tenantId":"%s"}
                """.formatted(forgedNumber, otherTenant))).andReturn();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("preview-fixture", "fixture"));
             TenantContext.Scope ignored = TenantContext.use(otherTenant)) {
            assertThat(students.count(Criteria.of().eq("studentNo", forgedNumber))).isZero();
        }
        try (TenantContext.Scope ignored = TenantContext.system("disable preview fixture")) {
            tenantService.disable(otherTenant);
        }
        assertThat(mvc.perform(post("/education.exam/query").header(header, otherTenant)
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        var candidates = mvc.perform(post("/iam.tenant/navigator/reference/query")
                .contentType("application/json").content("{}")).andReturn().getResponse();
        assertThat(candidates.getStatus()).isEqualTo(200);
        assertThat(candidates.getContentAsString()).doesNotContain(otherTenant);
        identity.set(CurrentUser.tenantUser("preview-user", "user", DemoBootstrapTask.TENANT_ALIAS));
        assertThat(mvc.perform(post("/education.exam/query").header(header, otherTenant)
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(TenantContext.hasContext()).isFalse();
        assertThat(CurrentUserContext.currentUser()).isEmpty();
        assertThat(runtimeContexts.context("education.student").tenantRequired()).isTrue();
        assertThat(runtimeContexts.context("iam.tenant").tenantRequired()).isFalse();
    }

    @Test
    void shouldRequireTenantHeaderAndKeepEmployeeAndOrganizationPagesInTheSelectedTenant() throws Exception {
        String otherTenant = "employee_scope_" + serial();
        String otherOrganizationId = "employee_scope_org_" + serial();
        String otherDepartmentId = "employee_scope_dept_" + serial();
        String otherEmployeeId = "employee_scope_employee_" + serial();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("employee-scope-fixture", "fixture"));
             TenantContext.Scope ignored = TenantContext.system("employee scope fixture")) {
            var tenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            tenant.setAlias(otherTenant);
            tenant.setTitle("Employee scope tenant");
            tenant.setEnabled(true);
            tenantService.insert(tenant);
            tenantApplications.configureApplications(otherTenant, List.of("iam"));
        }
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("employee-scope-fixture", "fixture"));
             TenantContext.Scope ignored = TenantContext.use(otherTenant)) {
            var organization = new net.ximatai.muyun.spring.iam.organization.Organization();
            organization.setId(otherOrganizationId);
            organization.setCode("ORG-" + serial());
            organization.setTitle("Other tenant organization");
            organization.setEnabled(true);
            organizations.insert(organization);
            var department = new net.ximatai.muyun.spring.iam.department.Department();
            department.setId(otherDepartmentId);
            department.setOrganizationId(otherOrganizationId);
            department.setCode("DEPT-" + serial());
            department.setTitle("Other tenant department");
            department.setEnabled(true);
            departments.insert(department);
            var employee = new net.ximatai.muyun.spring.iam.employee.Employee();
            employee.setId(otherEmployeeId);
            employee.setOrganizationId(otherOrganizationId);
            employee.setDepartmentId(otherDepartmentId);
            employee.setEmployeeNo("EMP-" + serial());
            employee.setTitle("Other tenant employee");
            employee.setEnabled(true);
            employees.insert(employee);
        }

        var identity = new java.util.concurrent.atomic.AtomicReference<>(CurrentUser.systemUser("employee-scope-admin", "admin"));
        MockMvc mvc = webAppContextSetup(webApplicationContext)
                .addFilters(new net.ximatai.muyun.spring.web.CurrentUserWebFilter(
                        () -> java.util.Optional.of(identity.get()), requestTenantVerifier)).build();
        String tenantHeader = net.ximatai.muyun.spring.web.CurrentUserWebFilter.TENANT_HEADER;

        MockMvc anonymousMvc = webAppContextSetup(webApplicationContext)
                .addFilters(new net.ximatai.muyun.spring.web.CurrentUserWebFilter(
                        () -> java.util.Optional.empty(), requestTenantVerifier)).build();
        var anonymous = anonymousMvc.perform(post("/iam.employee/query").contentType("application/json")
                        .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(DemoBootstrapTask.ORGANIZATION_ID)))
                .andReturn().getResponse();
        assertThat(anonymous.getStatus()).as(anonymous.getContentAsString()).isEqualTo(401);
        assertThat(anonymous.getContentAsString()).contains("AUTH_REQUIRED");

        var employeeSchema = mvc.perform(get("/iam.employee/query/schema"))
                .andReturn().getResponse();
        assertThat(employeeSchema.getStatus()).as(employeeSchema.getContentAsString()).isEqualTo(200);
        var employeeFormSchema = mvc.perform(get("/iam.employee/form/schema"))
                .andReturn().getResponse();
        assertThat(employeeFormSchema.getStatus()).as(employeeFormSchema.getContentAsString()).isEqualTo(200);
        var examSchema = mvc.perform(get("/education.exam/query/schema"))
                .andReturn().getResponse();
        assertThat(examSchema.getStatus()).as(examSchema.getContentAsString()).isEqualTo(200);
        var employeeContext = mvc.perform(get("/platform.module/iam.employee/context"))
                .andReturn().getResponse();
        assertThat(employeeContext.getStatus()).as(employeeContext.getContentAsString()).isEqualTo(200);
        assertThat(employeeContext.getContentAsString()).contains("\"tenantRequired\":true");
        var examContext = mvc.perform(get("/platform.module/education.exam/context"))
                .andReturn().getResponse();
        assertThat(examContext.getStatus()).as(examContext.getContentAsString()).isEqualTo(200);
        assertThat(examContext.getContentAsString()).contains("\"tenantRequired\":true");
        var organizationReferenceContext = mvc.perform(get("/platform.module/iam.organization/reference-context"))
                .andReturn().getResponse();
        assertThat(organizationReferenceContext.getStatus())
                .as(organizationReferenceContext.getContentAsString()).isEqualTo(200);
        assertThat(organizationReferenceContext.getContentAsString()).contains("\"tenantRequired\":true");
        var systemMenuSchemes = mvc.perform(post("/platform.menu_scheme/query")
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse();
        assertThat(systemMenuSchemes.getStatus()).as(systemMenuSchemes.getContentAsString()).isEqualTo(200);
        assertThat(systemMenuSchemes.getContentAsString()).contains("platform.menu_scheme.admin");
        var systemMenuEntries = mvc.perform(post("/platform.menu/tree/query")
                        .contentType("application/json")
                        .content("{\"externalQueryValues\":{\"schemeId\":\"platform.menu_scheme.admin\"}}"))
                .andReturn().getResponse();
        assertThat(systemMenuEntries.getStatus()).as(systemMenuEntries.getContentAsString()).isEqualTo(200);
        assertThat(systemMenuEntries.getContentAsString()).contains("platform.menu.group.platform");

        var missingScope = mvc.perform(post("/iam.employee/query").contentType("application/json")
                .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(DemoBootstrapTask.ORGANIZATION_ID)))
                .andReturn().getResponse();
        assertThat(missingScope.getStatus()).as(missingScope.getContentAsString()).isEqualTo(400);
        assertThat(missingScope.getContentAsString()).contains("iam.employee requires tenant context");

        var demoEmployees = mvc.perform(post("/iam.employee/query").header(tenantHeader, DemoBootstrapTask.TENANT_ALIAS)
                        .contentType("application/json")
                        .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(DemoBootstrapTask.ORGANIZATION_ID)))
                .andReturn().getResponse();
        assertThat(demoEmployees.getStatus()).isEqualTo(200);
        assertThat(demoEmployees.getContentAsString()).contains(DemoBootstrapTask.EMPLOYEE_ID)
                .doesNotContain(otherEmployeeId);
        var otherEmployees = mvc.perform(post("/iam.employee/query").header(tenantHeader, otherTenant)
                        .contentType("application/json")
                        .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(otherOrganizationId)))
                .andReturn().getResponse();
        assertThat(otherEmployees.getStatus()).isEqualTo(200);
        assertThat(otherEmployees.getContentAsString()).contains(otherEmployeeId)
                .doesNotContain(DemoBootstrapTask.EMPLOYEE_ID);
        var crossTenantOrganization = mvc.perform(post("/iam.employee/query")
                        .header(tenantHeader, DemoBootstrapTask.TENANT_ALIAS)
                        .contentType("application/json")
                        .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(otherOrganizationId)))
                .andReturn().getResponse();
        assertThat(crossTenantOrganization.getStatus()).isEqualTo(200);
        assertThat(crossTenantOrganization.getContentAsString()).doesNotContain(otherEmployeeId);
        var hiddenOtherEmployee = mvc.perform(get("/iam.employee/view/{id}", otherEmployeeId)
                        .header(tenantHeader, DemoBootstrapTask.TENANT_ALIAS))
                .andReturn().getResponse();
        assertThat(hiddenOtherEmployee.getStatus()).as(hiddenOtherEmployee.getContentAsString()).isEqualTo(404);

        var demoOrganizations = mvc.perform(get("/iam.organization/tree?flat=true")
                        .header(tenantHeader, DemoBootstrapTask.TENANT_ALIAS))
                .andReturn().getResponse();
        assertThat(demoOrganizations.getStatus()).isEqualTo(200);
        assertThat(demoOrganizations.getContentAsString()).contains(DemoBootstrapTask.ORGANIZATION_ID)
                .doesNotContain(otherOrganizationId);
        var otherOrganizations = mvc.perform(get("/iam.organization/tree?flat=true").header(tenantHeader, otherTenant))
                .andReturn().getResponse();
        assertThat(otherOrganizations.getStatus()).isEqualTo(200);
        assertThat(otherOrganizations.getContentAsString()).contains(otherOrganizationId)
                .doesNotContain(DemoBootstrapTask.ORGANIZATION_ID);

        identity.set(CurrentUser.tenantUser("employee-scope-user", "User", DemoBootstrapTask.TENANT_ALIAS));
        var forgedTenantHeader = mvc.perform(post("/iam.employee/query").header(tenantHeader, otherTenant)
                        .contentType("application/json")
                        .content("{\"externalQueryValues\":{\"organizationId\":\"%s\"}}"
                                .formatted(otherOrganizationId)))
                .andReturn().getResponse();
        assertThat(forgedTenantHeader.getStatus()).as(forgedTenantHeader.getContentAsString()).isEqualTo(403);
    }

    @Test
    void shouldRegisterDeliveredSchoolApplicationModulesAndTheirAbilityEndpoints() {
        assertThat(applicationService.select("education")).satisfies(application -> {
            assertThat(application.getTitle()).isEqualTo("教学管理");
            assertThat(application.getSystemManaged()).isTrue();
        });
        assertThat(endpointCatalog.endpoints()).extracting(endpoint -> endpoint.definition().endpointId())
                .contains("education.student.enable.enable", "education.student.recycleBin.query",
                        "education.teacher.enable.disable", "education.classroom.sort.sort",
                        "education.classroom.recycleBin.restore", "education.subject_category.tree.tree",
                        "education.subject_category.tree.sort");
    }

    @Test
    void shouldPlaceTeachingDemoModulesInTheirOwnMenuGroup() {
        try (TenantContext.Scope ignored = TenantContext.system("inspect teaching demo menu group")) {
            assertThat(menus.select(TeachingDemoMenuGroups.ROOT)).satisfies(group -> {
                assertThat(group.getTitle()).isEqualTo("教学演示");
                assertThat(group.getParentId()).isEqualTo(MenuService.ADMIN_PLATFORM_GROUP_ID);
            });
            assertThat(List.of("education.student", "education.classroom", "education.teacher",
                    "education.subject_category", ExamDemoBootstrapTask.MODULE_ALIAS))
                    .allSatisfy(moduleAlias -> assertThat(menus.select("platform.menu.module." + moduleAlias))
                            .extracting(Menu::getParentId)
                            .isEqualTo(TeachingDemoMenuGroups.ROOT));
            assertThat(menus.select(SchoolDemoMountTask.MENU_ID))
                    .extracting(Menu::getModuleAlias)
                    .isEqualTo(ExamDemoBootstrapTask.MODULE_ALIAS);
        }
    }

    @Test
    void shouldRemountTheCompleteDemoWithoutChangingOrdinaryMenuChoicesOrBusinessData() {
        var moduleService = webApplicationContext.getBean(PlatformModuleService.class);
        var mount = webApplicationContext.getBean(SchoolDemoMountTask.class);
        try (TenantContext.Scope ignored = TenantContext.system("verify demo remount")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                    SchoolDemoMountTask.MODULE_ALIASES.forEach(alias -> {
                        moduleService.disable(alias);
                        menus.disable("platform.menu.module." + alias);
                    });
                    menus.disable(TeachingDemoMenuGroups.ROOT);
                    menus.disable("platform.menu.module.platform.application");
                });
                var studentBefore = students.select("demo_student_1001");
                assertThat(studentBefore).isNotNull();
                mount.run();
                mount.run();
                SchoolDemoMountTask.MODULE_ALIASES.forEach(alias -> {
                    assertThat(moduleService.select(alias).getEnabled()).as(alias).isTrue();
                    assertThat(menus.select("platform.menu.module." + alias).getEnabled()).as(alias).isTrue();
                });
                assertThat(menus.select(TeachingDemoMenuGroups.ROOT).getEnabled()).isTrue();
                assertThat(menus.select("platform.menu.module.platform.application").getEnabled()).isFalse();
                assertThat(students.select("demo_student_1001")).usingRecursiveComparison().isEqualTo(studentBefore);
                webApplicationContext.getBean(net.ximatai.muyun.spring.iam.tenant.TenantMenuReconciliationTask.class)
                        .run();
                try (TenantContext.Scope tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                    var demoMenus = menus.list(Criteria.of()).stream()
                            .filter(menu -> menu.getModuleAlias() != null
                                    && SchoolDemoMountTask.MODULE_ALIASES.contains(menu.getModuleAlias()))
                            .toList();
                    assertThat(demoMenus).hasSize(5).allSatisfy(menu -> assertThat(menu.getEnabled()).isTrue());
                }
                status.setRollbackOnly();
            });
        }
    }

    @Test
    void shouldSupportSubjectTreeAndResolveTeacherSubjectTitle() {
        String serial = serial();
        try (TenantContext.Scope ignored = TenantContext.use("campus-subject")) {
            String scienceId = subjects.insert(subject("science-" + serial, "理科", TreeAbility.ROOT_ID));
            String mathematicsId = subjects.insert(subject("mathematics-" + serial, "数学", scienceId));
            Teacher teacher = teacher("T-" + serial, "数学老师", mathematicsId);
            String teacherId = teachers.insert(teacher);

            assertThat(subjects.children(scienceId)).extracting(SubjectCategory::getId).containsExactly(mathematicsId);
            assertThat(teachers.select(teacherId).getSubjectTitle()).isEqualTo("数学");
            // Change storage directly to distinguish dependency invalidation from merely
            // reloading a projected title on an otherwise stale cached teacher.
            Teacher storedTeacher = teachers.selectActiveRaw(teacherId);
            storedTeacher.setTitle("更新后的数学老师");
            teachers.getDao().updateById(storedTeacher);
            assertThat(teachers.select(teacherId).getTitle()).isEqualTo("数学老师");
            teachers.selectAllWithCache();
            // A transaction-local draft must not redirect the shared cache's dependencies.
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                teachers.select(teacherId).setSubjectCategoryId(scienceId);
                teachers.selectAllWithCache().stream().filter(row -> row.getId().equals(teacherId))
                        .forEach(row -> row.setSubjectCategoryId(scienceId));
            });

            SubjectCategory mathematics = subjects.select(mathematicsId);
            mathematics.setTitle("高等数学");
            assertThat(subjects.update(mathematics)).isEqualTo(1);
            assertThat(teachers.select(teacherId).getTitle()).isEqualTo("更新后的数学老师");
            assertThat(teachers.select(teacherId).getSubjectTitle()).isEqualTo("高等数学");
        }
    }

    @Test
    void shouldRejectTeacherWithUnknownSubjectCategory() {
        try (TenantContext.Scope ignored = TenantContext.use("campus-subject-integrity")) {
            Teacher teacher = teacher("T-" + serial(), "引用校验老师", "unknown-subject-category");

            assertThatThrownBy(() -> teachers.insert(teacher))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("所选关联记录不存在或已删除")
                    .satisfies(error -> assertThat(((PlatformException) error).details())
                            .containsEntry("referenceReason", "TARGET_UNAVAILABLE"));
        }
    }

    @Test
    void shouldBootstrapUnifiedAcademicEvaluationWithStaticReferenceTargets() {
        Metadata exam;
        Metadata participant;
        ModuleMetadataRelation main;
        ModuleMetadataRelation participants;
        try (TenantContext.Scope ignored = TenantContext.system("inspect academic evaluation metadata")) {
            exam = metadata("exam");
            participant = metadata("exam_participant");
            main = relation(exam.getId(), RelationRole.MAIN);
            participants = relation(participant.getId(), RelationRole.CHILD);

            MetadataField classroomId = field(exam.getId(), "classroomId");
            MetadataField subjectCategoryId = field(exam.getId(), "subjectCategoryId");
            MetadataField studentId = field(participant.getId(), "studentId");

            assertThat(metadataFields.list(Criteria.of().eq("metadataId", exam.getId())))
                    .extracting(MetadataField::getFieldName)
                    .containsExactlyInAnyOrder("title", "classroomId", "subjectCategoryId", "examDate");
            assertThat(metadataFields.list(Criteria.of().eq("metadataId", participant.getId())))
                    .extracting(MetadataField::getFieldName)
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                            net.ximatai.muyun.spring.common.schema.StandardEntitySchema.fieldNames().stream(),
                            java.util.stream.Stream.of("examId", "studentId", "score", "attendanceStatus")).toList());
            assertThat(referenceConfigs.findForRelation(classroomId.getId(), main.getId()))
                    .satisfies(config -> {
                        assertThat(config.getTargetModuleAlias()).isEqualTo(ClassroomService.MODULE_ALIAS);
                        assertThat(config.getTargetMetadataId()).isNull();
                        assertThat(config.getProjectionMappings()).isEqualTo("title:classroomIdTitle,classCode:classroomCode");
                    });
            assertThat(referenceConfigs.findForRelation(subjectCategoryId.getId(), main.getId()))
                    .satisfies(config -> {
                        assertThat(config.getTargetModuleAlias()).isEqualTo(SubjectCategoryService.MODULE_ALIAS);
                        assertThat(config.getProjectionMappings()).isEqualTo("title:subjectCategoryIdTitle,code:subjectCategoryCode");
                    });
            assertThat(referenceConfigs.findForRelation(studentId.getId(), participants.getId()))
                    .satisfies(config -> {
                        assertThat(config.getTargetModuleAlias()).isEqualTo(StudentService.MODULE_ALIAS);
                        assertThat(config.getProjectionMappings()).isEqualTo("title:studentIdTitle,studentNo:studentNo");
                    });
            assertThat(fieldConfigs.findRelationOverride(field(participant.getId(), "attendanceStatus").getId(),
                    participants.getId())).satisfies(config -> {
                assertThat(config.getDictionaryApplicationAlias()).isEqualTo("education");
                assertThat(config.getDictionaryCategoryAlias()).isEqualTo("exam_attendance_status");
            });
        }

        try (TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            DynamicRecord examRecord = dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam",
                    Criteria.of().eq("title", "2026 春季期中数学测评"), PageRequest.of(1, 1)).getFirst();
            assertThat(examRecord.getValue("classroomId")).isEqualTo("demo_classroom_g1a");
            assertThat(examRecord.getValue("classroomIdTitle")).isEqualTo("高一（1）班");
            assertThat(examRecord.getValue("classroomCode")).isEqualTo("G1-A");
            assertThat(examRecord.getValue("subjectCategoryIdTitle")).isEqualTo("数学");
            assertThat(examRecord.getValue("subjectCategoryCode")).isEqualTo("mathematics");

            List<DynamicRecord> rows = dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS,
                    "exam_participant", Criteria.of().eq("examId", examRecord.getId()));
            assertThat(rows).extracting(row -> row.getValue("studentId"))
                    .containsExactlyInAnyOrder("demo_student_1001", "demo_student_1002");
            assertThat(rows).extracting(row -> row.getValue("studentNo"))
                    .containsExactlyInAnyOrder("S2026001", "S2026002");
            assertThat(rows).extracting(row -> row.getValue("studentIdTitle"))
                    .containsExactlyInAnyOrder("陈晨", "林晓");
        }
    }

    @Test
    void shouldRegisterGovernableStandardActionsForDynamicExamModule() {
        try (TenantContext.Scope ignored = TenantContext.system("inspect academic evaluation actions")) {
            List<PlatformModuleAction> actions = moduleActions.listByModuleAliases(
                    List.of(ExamDemoBootstrapTask.MODULE_ALIAS));
            assertThat(actions)
                    .extracting(PlatformModuleAction::getActionCode)
                    .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "batchDelete", "query",
                            "reference", ExamPostponeActionExecutor.ACTION_CODE);
            assertThat(actions.stream()
                    .filter(action -> !ExamPostponeActionExecutor.ACTION_CODE.equals(action.getActionCode()))
                    .toList())
                    .extracting(PlatformModuleAction::getSourceType)
                    .containsOnly(ModuleActionSourceType.DYNAMIC_MODULE);
            assertThat(actions.stream()
                    .filter(action -> ExamPostponeActionExecutor.ACTION_CODE.equals(action.getActionCode()))
                    .findFirst().orElseThrow())
                    .extracting(PlatformModuleAction::getSourceType, PlatformModuleAction::getExecutorKey)
                    .containsExactly(ModuleActionSourceType.CODE_EXTENSION, ExamPostponeActionExecutor.EXECUTOR_KEY);
        }
    }

    @Test
    void shouldPublishExamManagementPageAndKeepAnEditableFollowUpDraft() {
        try (TenantContext.Scope ignored = TenantContext.system("inspect academic evaluation page baseline")) {
            PlatformPageDefinition page = pageDefinitions.resolveGlobalPage(ExamDemoBootstrapTask.MODULE_ALIAS,
                    ExamPageDemoBootstrapTask.PAGE_ALIAS).orElseThrow();
            assertThat(page.getMainRelationId()).isEqualTo(relation(metadata("exam").getId(), RelationRole.MAIN).getId());

            PlatformPresentationVariant variant = presentationVariants.list(Criteria.of().eq("pageId", page.getId()))
                    .getFirst();
            List<PlatformPresentationRevision> revisions = presentationRevisions.list(
                    Criteria.of().eq("variantId", variant.getId()));
            PlatformPresentationRevision published = revisions.stream()
                    .filter(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED)
                    .findFirst().orElseThrow();
            PlatformPresentationRevision draft = revisions.stream()
                    .filter(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.DRAFT)
                    .filter(revision -> published.getUiTreeJson().equals(revision.getUiTreeJson()))
                    .max(java.util.Comparator.comparing(PlatformPresentationRevision::getRevisionNo)).orElseThrow();
            assertThat(draft.getRevisionNo()).isGreaterThan(published.getRevisionNo());
            assertThat(draft.getUiTreeJson()).isEqualTo(published.getUiTreeJson());
            assertThat(published.getUiTreeJson()).contains("searchPlaceholder", "participants")
                    .doesNotContain("\"studentNo\"", "\"studentIdTitle\"");

            ModuleExecutionPlan plan = runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS)
                    .orElseThrow();
            assertThat(plan.versionKey()).contains("-page-" + published.getId(), "-r" + published.getRevisionNo());
            assertThat(plan.uiDescriptor().page().list().fields().fields())
                    .extracting(field -> field.fieldRef().fieldName())
                    .containsExactly("title", "classroomId", "subjectCategoryId", "examDate");
            assertThat(plan.uiDescriptor().page().detail().editor().fields())
                    .extracting(field -> field.fieldRef().fieldName())
                    .containsExactly("title", "classroomId", "subjectCategoryId", "examDate");
            assertThat(plan.mutationFieldValidations()).extracting(validation -> validation.fieldName())
                    .containsExactly("title", "classroomId", "subjectCategoryId", "examDate")
                    .doesNotContain("studentNo", "studentIdTitle");
            assertThat(plan.uiDescriptor().detailRelations()).singleElement().satisfies(participants -> {
                assertThat(participants.code()).isEqualTo("participants");
                assertThat(participants.targetEntityAlias()).isEqualTo("exam_participant");
                assertThat(participants.readOnly()).isFalse();
                assertThat(participants.embeddedField()).isEqualTo("participants");
                assertThat(participants.editing().saveMode().name()).isEqualTo("AGGREGATE_DRAFT");
                assertThat(participants.mutationContract()).satisfies(mutations -> {
                    assertThat(mutations.createAllowed()).isTrue();
                    assertThat(mutations.updateAllowed()).isTrue();
                    assertThat(mutations.deleteAllowed()).isTrue();
                });
                assertThat(participants.queryContract().listProjection().fields())
                        .extracting(field -> field.fieldName())
                        .containsExactly("studentId", "score", "attendanceStatus");
                assertThat(participants.queryContract().listProjection().fields())
                        .filteredOn(field -> field.fieldName().equals("studentId")
                                || field.fieldName().equals("attendanceStatus"))
                        .extracting(field -> field.title())
                        .containsExactly("学生", "参加状态");
            });
            assertThat(plan.uiDescriptor().editorContributions()).singleElement().satisfies(contribution ->
                    assertThat(contribution.editor().fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("studentId", "score", "attendanceStatus"));
            assertThat(plan.uiDescriptor().editorContributions().getFirst().editor().fields())
                    .filteredOn(field -> field.fieldRef().fieldName().equals("studentId"))
                    .singleElement().satisfies(field -> {
                        assertThat(field.reference()).isNotNull();
                        assertThat(field.reference().titleField()).isEqualTo("studentIdTitle");
                        assertThat(field.reference().displayProjections())
                                .containsExactly(new ResolvedReferenceDisplayProjectionDescriptor("title", "studentIdTitle"),
                                        new ResolvedReferenceDisplayProjectionDescriptor("studentNo", "studentNo"));
                    });
            assertThat(plan.uiDescriptor().editorContributions().getFirst().editor().fields())
                    .filteredOn(field -> field.fieldRef().fieldName().equals("attendanceStatus"))
                    .singleElement().satisfies(field -> assertThat(field.option()).isNotNull());

        }
    }

    @Test
    void shouldNeverRewriteUserOwnedExamPageDraftWhenDemoBootstrapRunsAgain() {
        try (TenantContext.Scope ignored = TenantContext.system("protect user-owned exam page draft")) {
            PlatformPageDefinition page = pageDefinitions.resolveGlobalPage(ExamDemoBootstrapTask.MODULE_ALIAS,
                    ExamPageDemoBootstrapTask.PAGE_ALIAS).orElseThrow();
            PlatformPresentationVariant variant = presentationVariants.list(Criteria.of()
                    .eq("pageId", page.getId())).getFirst();
            PlatformPresentationRevision userDraft = new PlatformPresentationRevision();
            userDraft.setVariantId(variant.getId());
            userDraft.setRevisionNo(presentationRevisions.list(Criteria.of().eq("variantId", variant.getId()))
                    .stream().map(PlatformPresentationRevision::getRevisionNo).max(Integer::compareTo).orElse(0) + 1);
            userDraft.setTemplateAlias("management");
            userDraft.setTemplateVersion(1);
            userDraft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            userDraft.setTitle("用户自定义考试管理页草稿");
            userDraft.setUiTreeJson("{\"nodes\":[{\"fields\":[\"studentId\",\"studentNo\",\"studentTitle\"]}]}");
            String draftId = presentationRevisions.insert(userDraft);

            examPageBootstrap.run();

            assertThat(presentationRevisions.select(draftId).getUiTreeJson())
                    .contains("studentTitle");
        }
    }

    @Test
    void shouldExposeReferenceProjectionsThroughTheRealExamParticipantAssociationQuery() {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "inspect-exam-participant-projections", "Exam Participant Projection Inspection"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            DynamicRecord exam = dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam",
                    Criteria.of().eq("title", "2026 春季期中数学测评"), PageRequest.of(1, 1)).getFirst();
            assertThat(dynamicRecords.associationViewPage(ExamDemoBootstrapTask.MODULE_ALIAS, "exam", exam.getId(),
                    "participants", Criteria.of(), PageRequest.of(1, 20)).getRecords())
                    .allSatisfy(participant -> {
                        assertThat(String.valueOf(participant.getValue("studentNo"))).isNotBlank();
                        assertThat(String.valueOf(participant.getValue("studentIdTitle"))).isNotBlank();
                    });
        }
    }

    @Test
    void shouldUseLiteralSubstringSearchOnThePublishedDynamicExamPage() throws Exception {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser("exam-search-test", "Search Test"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            var found = mvc.perform(post("/education.exam/query").contentType("application/json")
                    .content("{\"quickSearch\":\"数学\",\"quickSearchFields\":[\"title\"]}"))
                    .andReturn().getResponse();
            assertThat(found.getStatus()).as(found.getContentAsString()).isEqualTo(200);
            assertThat(found.getContentAsString()).contains("2026 春季期中数学测评").doesNotContain("2026 春季英语听力测试");
            var escaped = mvc.perform(post("/education.exam/query").contentType("application/json")
                    .content("{\"quickSearch\":\"%\",\"quickSearchFields\":[\"title\"]}"))
                    .andReturn().getResponse();
            assertThat(escaped.getStatus()).as(escaped.getContentAsString()).isEqualTo(200);
            assertThat(escaped.getContentAsString()).doesNotContain("2026 春季期中数学测评", "2026 春季英语听力测试");
        }
    }

    @Test
    void shouldExposeReferenceTitlesThroughTheExamListWebResponse() throws Exception {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "inspect-exam-list-projections", "Exam List Projection Inspection"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            MvcResult result = mvc.perform(post("/education.exam/query")
                    .contentType("application/json").content("{}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
            String response = result.getResponse().getContentAsString();
            assertThat(response).contains("\"classroomId\":\"demo_classroom_g1a\"",
                    "\"classroomIdTitle\":\"高一（1）班\"",
                    "\"subjectCategoryId\":\"demo_subject_mathematics\"",
                    "\"subjectCategoryIdTitle\":\"数学\"");
        }
    }

    @Test
    void shouldExposeReferenceTitlesThroughTheEmbeddedExamParticipantViewResponse() throws Exception {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "inspect-exam-detail-projections", "Exam Detail Projection Inspection"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            DynamicRecord exam = dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam",
                    Criteria.of().eq("title", "2026 春季期中数学测评"), PageRequest.of(1, 1)).getFirst();
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            MvcResult result = mvc.perform(get("/education.exam/view/{id}", exam.getId())).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"studentId\":\"demo_student_1001\"", "\"studentIdTitle\":\"陈晨\"");
        }
    }

    @Test
    void shouldResolveAttendanceDictionaryOptionsForTheExamParticipantEntity() throws Exception {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "inspect-exam-participant-options", "Exam Participant Option Inspection"));
             TenantContext.Scope ignored = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            MvcResult result = mvc.perform(get("/platform.module/education.exam/fields/attendanceStatus/options")
                    .param("entityAlias", "exam_participant")
                    .param("enabledOnly", "false"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":\"ATTENDED\"", "\"title\":\"已参加\"");
        }
    }

    @Test
    void shouldPublishManagedActionEntriesAndSwitchOnlyAfterPublication() {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "exam-action-publication", "Exam Action Publication"));
             TenantContext.Scope tenant = TenantContext.system("verify global page action publication")) {
            PlatformPageDefinition page = pageDefinitions.resolveGlobalPage(ExamDemoBootstrapTask.MODULE_ALIAS,
                    ExamPageDemoBootstrapTask.PAGE_ALIAS).orElseThrow();
            PlatformPresentationVariant variant = presentationVariants.list(Criteria.of().eq("pageId", page.getId()))
                    .getFirst();
            String variantId = variant.getId();
            List<PlatformPresentationRevision> revisions = presentationRevisions.list(Criteria.of().eq("variantId", variantId));
            PlatformPresentationRevision baseline = revisions.stream()
                    .filter(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED)
                    .findFirst().orElseThrow();
            int nextRevision = revisions.stream().mapToInt(PlatformPresentationRevision::getRevisionNo).max().orElseThrow() + 1;
            try {
                String tree = """
                        {"template":"management","templateVersion":4,"mode":"LIST_CARD","quickSearchFields":[],
                         "actions":[{"actionCode":"create","anchor":"page"},
                                    {"actionCode":"update","anchor":"detail"},
                                    {"actionCode":"create","anchor":"form"},
                                    {"actionCode":"update","anchor":"form"}],
                         "nodes":[{"slot":"list","title":"考试列表","fields":["title"]},
                                  {"slot":"form","title":"考试详情","fields":["title","classroomId","subjectCategoryId","examDate"]}]}
                        """;
                PlatformPresentationRevision draft = new PlatformPresentationRevision();
                draft.setVariantId(variantId);
                draft.setRevisionNo(nextRevision);
                draft.setTemplateAlias("management");
                draft.setTemplateVersion(4);
                draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
                draft.setTitle("动作入口验收 v1");
                draft.setUiTreeJson(tree);
                String draftId = presentationRevisions.insert(draft);
                assertThat(runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS).orElseThrow()
                        .versionKey()).doesNotContain("-page-" + draftId);
                presentationRevisionPublisher.publish(draftId);
                ModuleExecutionPlan published = runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS)
                        .orElseThrow();
                assertThat(published.versionKey()).contains("-page-" + draftId);
                assertThat(published.uiDescriptor().page().managedActions()).isTrue();
                assertThat(published.uiDescriptor().page().actions()).extracting(action -> action.operation().name())
                        .containsExactly("OPEN_CREATE", "OPEN_EDIT", "SUBMIT_CREATE", "SUBMIT_UPDATE");

                PlatformPresentationRevision replacement = new PlatformPresentationRevision();
                replacement.setVariantId(variantId);
                replacement.setRevisionNo(nextRevision + 1);
                replacement.setTemplateAlias("management");
                replacement.setTemplateVersion(4);
                replacement.setStatus(PlatformPresentationRevisionStatus.DRAFT);
                replacement.setTitle("动作入口验收 v2");
                replacement.setUiTreeJson(tree.replace("{\"actionCode\":\"create\",\"anchor\":\"page\"},", ""));
                String replacementId = presentationRevisions.insert(replacement);
                assertThat(runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS).orElseThrow()
                        .versionKey()).contains("-page-" + draftId);
                presentationRevisionPublisher.publish(replacementId);
                assertThat(presentationRevisions.select(draftId).getStatus())
                        .isEqualTo(PlatformPresentationRevisionStatus.ARCHIVED);
                ModuleExecutionPlan switched = runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS)
                        .orElseThrow();
                assertThat(switched.versionKey()).contains("-page-" + replacementId);
                assertThat(switched.uiDescriptor().page().actions()).extracting(action -> action.operation().name())
                        .containsExactly("OPEN_EDIT", "SUBMIT_CREATE", "SUBMIT_UPDATE");
            } finally {
                PlatformPresentationRevision restored = new PlatformPresentationRevision();
                restored.setVariantId(variantId);
                restored.setRevisionNo(nextRevision + 2);
                restored.setTemplateAlias(baseline.getTemplateAlias());
                restored.setTemplateVersion(baseline.getTemplateVersion());
                restored.setStatus(PlatformPresentationRevisionStatus.DRAFT);
                restored.setTitle("恢复考试页面基线");
                restored.setUiTreeJson(baseline.getUiTreeJson());
                presentationRevisionPublisher.publish(presentationRevisions.insert(restored));
                PlatformPresentationRevision followUp = new PlatformPresentationRevision();
                followUp.setVariantId(variantId);
                followUp.setRevisionNo(nextRevision + 3);
                followUp.setTemplateAlias(baseline.getTemplateAlias());
                followUp.setTemplateVersion(baseline.getTemplateVersion());
                followUp.setStatus(PlatformPresentationRevisionStatus.DRAFT);
                followUp.setTitle("考试页面基线后续草稿");
                followUp.setUiTreeJson(baseline.getUiTreeJson());
                presentationRevisions.insert(followUp);
            }
        }
    }

    @Test
    void shouldPersistPublishedExamAggregateThroughHttpAndEnforceActionAndTenantBoundaries() throws Exception {
        String title = "页面业务验收-" + serial();
        String recordId;
        String firstChildId;
        MockMvc mvc = webAppContextSetup(webApplicationContext).build();
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "exam-business-acceptance", "Exam Business Acceptance"));
             TenantContext.Scope tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            assertThat(runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS)
                    .orElseThrow().uiDescriptor().detailRelations())
                    .singleElement().satisfies(relation -> assertThat(relation.embeddedField()).isEqualTo("participants"));
            String body = """
                    {"values":{"title":"%s","classroomId":"demo_classroom_g1a",
                     "subjectCategoryId":"demo_subject_mathematics","examDate":"2026-09-07"},
                     "children":{"participants":[
                       {"values":{"studentId":"demo_student_1001","score":81,"attendanceStatus":"ATTENDED"}},
                       {"values":{"studentId":"demo_student_1002","score":82,"attendanceStatus":"ATTENDED"}}]}}
                    """.formatted(title);
            MvcResult created = mvc.perform(post("/education.exam/insert")
                    .contentType("application/json").content(body)).andReturn();
            assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
            DynamicRecord saved = dynamicRecords.mainEntity(ExamDemoBootstrapTask.MODULE_ALIAS)
                    .list(Criteria.of().eq("title", title), PageRequest.of(1, 10)).getFirst();
            recordId = saved.getId();
            List<DynamicRecord> rows = dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS,
                    "exam_participant", Criteria.of().eq("examId", recordId));
            assertThat(rows).hasSize(2);
            DynamicRecord first = rows.stream().filter(row -> "demo_student_1001".equals(row.getValue("studentId")))
                    .findFirst().orElseThrow();
            firstChildId = first.getId();
            String update = """
                    {"version":%d,"values":{"title":"%s-更新","classroomId":"demo_classroom_g1a",
                     "subjectCategoryId":"demo_subject_mathematics","examDate":"2026-09-07"},"children":{"participants":[
                      {"id":"%s","version":%d,"values":{"studentId":"demo_student_1001",
                       "score":95,"attendanceStatus":"ATTENDED"}}]}}
                    """.formatted(saved.getVersion(), title, firstChildId, first.getVersion());
            MvcResult updated = mvc.perform(post("/education.exam/update/{id}", recordId)
                    .contentType("application/json").content(update)).andReturn();
            assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(dynamicRecords.mainEntity(ExamDemoBootstrapTask.MODULE_ALIAS).select(recordId).getValue("title"))
                    .isEqualTo(title + "-更新");
            assertThat(dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam_participant",
                    Criteria.of().eq("examId", recordId))).singleElement().satisfies(row -> {
                        assertThat(row.getId()).isEqualTo(firstChildId);
                        assertThat(new java.math.BigDecimal(row.getValue("score").toString()))
                                .isEqualByComparingTo("95");
                    });
            MvcResult viewed = mvc.perform(get("/education.exam/view/{id}", recordId)).andReturn();
            assertThat(viewed.getResponse().getStatus()).as(viewed.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(viewed.getResponse().getContentAsString()).contains(title + "-更新", "陈晨")
                    .doesNotContain("demo_student_1002");
        }
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.tenantUser(
                "exam-no-grants-" + serial(), "未授权用户", DemoBootstrapTask.TENANT_ALIAS));
             TenantContext.Scope tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            MvcResult denied = mvc.perform(post("/education.exam/update/{id}", recordId)
                    .contentType("application/json").content("{\"values\":{\"title\":\"越权修改\"}}"))
                    .andReturn();
            assertThat(denied.getResponse().getStatus()).as(denied.getResponse().getContentAsString()).isEqualTo(403);
        }
        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser(
                "exam-tenant-boundary", "Exam Tenant Boundary"));
             TenantContext.Scope tenant = TenantContext.use("exam-other-tenant")) {
            assertThat(dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam",
                    Criteria.of().eq("id", recordId))).isEmpty();
            assertThat(dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam_participant",
                    Criteria.of().eq("examId", recordId))).isEmpty();
        }
        try (TenantContext.Scope tenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
            assertThat(dynamicRecords.listSystem(ExamDemoBootstrapTask.MODULE_ALIAS, "exam",
                    Criteria.of().eq("id", recordId))).singleElement()
                    .satisfies(row -> assertThat(row.getValue("title")).isEqualTo(title + "-更新"));
        }
    }

    @Test
    void shouldSwitchDynamicExecutionPlanWhenPublishingTheFollowUpDraft() {
        try (TenantContext.Scope ignored = TenantContext.system("publish academic evaluation page follow-up draft")) {
            PlatformPageDefinition page = pageDefinitions.resolveGlobalPage(ExamDemoBootstrapTask.MODULE_ALIAS,
                    ExamPageDemoBootstrapTask.PAGE_ALIAS).orElseThrow();
            PlatformPresentationVariant variant = presentationVariants.list(Criteria.of().eq("pageId", page.getId()))
                    .getFirst();
            PlatformPresentationRevision draft = presentationRevisions.list(Criteria.of().eq("variantId", variant.getId()))
                    .stream().filter(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.DRAFT)
                    .findFirst().orElseThrow();

            PlatformPresentationRevision published = presentationRevisionPublisher.publish(draft.getId());
            ModuleExecutionPlan plan = runtimeContexts.dynamicExecutionPlan(ExamDemoBootstrapTask.MODULE_ALIAS)
                    .orElseThrow();
            assertThat(published.getStatus()).isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);
            assertThat(plan.versionKey()).contains("-page-" + published.getId(), "-r" + published.getRevisionNo());

            PlatformPresentationRevision nextDraft = new PlatformPresentationRevision();
            nextDraft.setVariantId(variant.getId());
            nextDraft.setRevisionNo(presentationRevisions.list(Criteria.of().eq("variantId", variant.getId()))
                    .stream().mapToInt(PlatformPresentationRevision::getRevisionNo).max().orElseThrow() + 1);
            nextDraft.setTemplateAlias(published.getTemplateAlias());
            nextDraft.setTemplateVersion(published.getTemplateVersion());
            nextDraft.setUiTreeJson(published.getUiTreeJson());
            nextDraft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            nextDraft.setEnabled(Boolean.TRUE);
            nextDraft.setTitle("考试管理页后续草稿");
            presentationRevisions.insert(nextDraft);
        }
    }


    @Test
    void shouldResolveHomeroomTeacherAndPopulateClassMembers() {
        try (TenantContext.Scope ignored = TenantContext.system("school demo aggregate")) {
            String assistantId = students.insert(student("S-" + serial(), "李同学", "二年级"));
            String subjectId = subjects.insert(subject("mathematics-" + serial(), "数学", TreeAbility.ROOT_ID));
            Teacher homeroomTeacher = teacher("T-" + serial(), "王老师", subjectId);
            homeroomTeacher.setStudentAssistantId(assistantId);
            String teacherId = teachers.insert(homeroomTeacher);
            String studentId = students.insert(student("S-" + serial(), "陈同学", "二年级"));
            ClassMember member = classMember(studentId);
            Classroom classroom = classroom("G2-" + serial(), "二年级一班", "2026", teacherId);
            classroom.setMembers(List.of(member));

            String classroomId = classrooms.insert(classroom);
            classroom.setMembers(null);
            Classroom selected = classrooms.select(classroomId);

            assertThat(selected.getHomeroomTeacherTitle()).isEqualTo("王老师");
            assertThat(selected.getMembers()).singleElement().satisfies(loaded -> {
                assertThat(loaded.getClassroomId()).isEqualTo(classroomId);
                assertThat(loaded.getSortOrder()).isEqualTo(100);
            });
            assertThat(members.select(member.getId()).getStudentTitle()).isEqualTo("陈同学");
            assertThat(members.select(member.getId()).getHomeroomTeacherAssistantTitle()).isEqualTo("李同学");
            assertThat(students.select(studentId).getClassMemberships())
                    .extracting(ClassMember::getId)
                    .containsExactly(member.getId());
        }
    }

    @Test
    void shouldRollbackDirectAggregateServiceInsertWhenAChildFails() {
        try (TenantContext.Scope ignored = TenantContext.system("aggregate rollback contract")) {
            String subjectId = subjects.insert(subject("rollback-" + serial(), "数学", TreeAbility.ROOT_ID));
            String teacherId = teachers.insert(teacher("T-" + serial(), "王老师", subjectId));
            String studentId = students.insert(student("S-" + serial(), "学生", "一年级"));
            ClassMember valid = classMember(studentId);
            Classroom parent = classroom("rollback-" + serial(), "回滚班级", "2026", teacherId);
            parent.setMembers(List.of(valid, classMember("missing-" + serial())));

            assertThatThrownBy(() -> classrooms.insert(parent)).isInstanceOf(RuntimeException.class);
            assertThat(classrooms.getDao().findById(parent.getId())).isNull();
            assertThat(members.getDao().query(Criteria.of().eq("classroomId", parent.getId()),
                    PageRequest.of(1, 10))).isEmpty();
        }
    }

    @Test
    void shouldReplaceMembersAndRestoreOnlyTheCurrentClassroomDeletionTree() {
        try (TenantContext.Scope ignored = TenantContext.system("school demo aggregate")) {
            String subjectId = subjects.insert(subject("mathematics-" + serial(), "数学", TreeAbility.ROOT_ID));
            String teacherId = teachers.insert(teacher("T-" + serial(), "王老师", subjectId));
            String firstStudentId = students.insert(student("S-" + serial(), "林晓", "三年级"));
            String removedStudentId = students.insert(student("S-" + serial(), "周然", "三年级"));
            String replacementStudentId = students.insert(student("S-" + serial(), "陈同学", "三年级"));
            ClassMember first = classMember(firstStudentId);
            ClassMember removed = classMember(removedStudentId);
            Classroom classroom = classroom("G3-" + serial(), "三年级一班", "2026", teacherId);
            classroom.setMembers(List.of(first, removed));
            String classroomId = classrooms.insert(classroom);

            ClassMember replacement = classMember(replacementStudentId);
            classroom.setMembers(List.of(first, replacement));
            assertThat(classrooms.update(classroom)).isEqualTo(1);
            assertThat(members.select(removed.getId())).isNull();
            assertThat(members.selectIgnoreSoftDelete(removed.getId())).isNotNull();

            assertThat(classrooms.delete(classroomId)).isEqualTo(1);
            assertThat(classrooms.select(classroomId)).isNull();
            assertThat(classrooms.pageRecycleBin(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                    .extracting(Classroom::getId).contains(classroomId);
            assertThat(members.select(first.getId())).isNull();
            assertThat(members.select(replacement.getId())).isNull();
            assertThat(members.selectIgnoreSoftDelete(first.getId())).isNotNull();
            assertThat(members.selectIgnoreSoftDelete(replacement.getId())).isNotNull();

            Classroom retained = classrooms.selectIgnoreSoftDelete(classroomId);
            var item = recycleBin.item(classrooms, retained, classroomId, retained.getDeletedAt());
            assertThat(item.restorable()).isTrue();
            assertThat(recycleBin.restore(classrooms, item.sourceDeleteOperationId()).entries())
                    .extracting(RestoreEntryResult::recordId, RestoreEntryResult::status)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(classroomId, RestoreEntryResult.Status.RESTORED),
                            org.assertj.core.groups.Tuple.tuple(first.getId(), RestoreEntryResult.Status.RESTORED),
                            org.assertj.core.groups.Tuple.tuple(replacement.getId(), RestoreEntryResult.Status.RESTORED));
            assertThat(classrooms.select(classroomId).getMembers())
                    .extracting(ClassMember::getId).containsExactlyInAnyOrder(first.getId(), replacement.getId());
            assertThat(members.select(first.getId())).isNotNull();
            assertThat(members.select(replacement.getId())).isNotNull();
            assertThat(members.select(removed.getId())).isNull();
            assertThat(members.selectIgnoreSoftDelete(removed.getId()).getDeleted()).isTrue();
        }
    }

    private SubjectCategory subject(String code, String title, String parentId) {
        SubjectCategory subject = new SubjectCategory();
        subject.setCode(code);
        subject.setTitle(title);
        subject.setParentId(parentId);
        return subject;
    }

    private Student student(String studentNo, String title, String grade) {
        Student student = new Student();
        student.setStudentNo(studentNo);
        student.setTitle(title);
        student.setGrade(grade);
        return student;
    }

    private Teacher teacher(String teacherNo, String title, String subjectCategoryId) {
        Teacher teacher = new Teacher();
        teacher.setTeacherNo(teacherNo);
        teacher.setTitle(title);
        teacher.setSubjectCategoryId(subjectCategoryId);
        return teacher;
    }

    private Classroom classroom(String classCode, String title, String academicYear, String homeroomTeacherId) {
        Classroom classroom = new Classroom();
        classroom.setClassCode(classCode);
        classroom.setTitle(title);
        classroom.setAcademicYear(academicYear);
        classroom.setHomeroomTeacherId(homeroomTeacherId);
        return classroom;
    }

    private ClassMember classMember(String studentId) {
        ClassMember member = new ClassMember();
        member.setStudentId(studentId);
        return member;
    }

    private Metadata metadata(String alias) {
        return metadataService.list(Criteria.of().eq("applicationAlias", "education").eq("alias", alias),
                        PageRequest.of(1, 1))
                .getFirst();
    }

    private MetadataField field(String metadataId, String fieldName) {
        return metadataFields.list(Criteria.of().eq("metadataId", metadataId).eq("fieldName", fieldName),
                        PageRequest.of(1, 1))
                .getFirst();
    }

    private ModuleMetadataRelation relation(String metadataId, RelationRole role) {
        return metadataRelations.list(Criteria.of().eq("moduleAlias", ExamDemoBootstrapTask.MODULE_ALIAS)
                        .eq("metadataId", metadataId).eq("relationRole", role), PageRequest.of(1, 1))
                .getFirst();
    }

    private String serial() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
