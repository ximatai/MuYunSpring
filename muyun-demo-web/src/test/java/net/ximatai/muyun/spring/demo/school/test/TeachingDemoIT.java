package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.demo.DemoBootstrapTask;
import net.ximatai.muyun.spring.demo.ExamDemoBootstrapTask;
import net.ximatai.muyun.spring.demo.ExamPageDemoBootstrapTask;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMember;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMemberService;
import net.ximatai.muyun.spring.demo.school.classroom.Classroom;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoConfiguration;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategory;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategoryService;
import net.ximatai.muyun.spring.demo.school.student.Student;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.teacher.Teacher;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
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
@SpringBootTest(classes = MuYunSpringApplication.class)
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
    private ClassMemberService members;

    @Autowired
    private ClassroomService classrooms;

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
    void shouldSupportSubjectTreeAndResolveTeacherSubjectTitle() {
        String serial = serial();
        try (TenantContext.Scope ignored = TenantContext.use("campus-subject")) {
            String scienceId = subjects.insert(subject("science-" + serial, "理科", TreeAbility.ROOT_ID));
            String mathematicsId = subjects.insert(subject("mathematics-" + serial, "数学", scienceId));
            Teacher teacher = teacher("T-" + serial, "数学老师", mathematicsId);
            String teacherId = teachers.insert(teacher);

            assertThat(subjects.children(scienceId)).extracting(SubjectCategory::getId).containsExactly(mathematicsId);
            assertThat(teachers.select(teacherId).getSubjectTitle()).isEqualTo("数学");

            SubjectCategory mathematics = subjects.select(mathematicsId);
            mathematics.setTitle("高等数学");
            assertThat(subjects.update(mathematics)).isEqualTo(1);
            assertThat(teachers.select(teacherId).getSubjectTitle()).isEqualTo("高等数学");
        }
    }

    @Test
    void shouldRejectTeacherWithUnknownSubjectCategory() {
        try (TenantContext.Scope ignored = TenantContext.use("campus-subject-integrity")) {
            Teacher teacher = teacher("T-" + serial(), "引用校验老师", "unknown-subject-category");

            assertThatThrownBy(() -> teachers.insert(teacher))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("reference target");
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
                    .containsExactlyInAnyOrder("examId", "studentId", "score", "attendanceStatus");
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
            assertThat(moduleActions.listByModuleAliases(List.of(ExamDemoBootstrapTask.MODULE_ALIAS)))
                    .extracting(PlatformModuleAction::getActionCode)
                    .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "batchDelete", "query",
                            "reference");
            assertThat(moduleActions.listByModuleAliases(List.of(ExamDemoBootstrapTask.MODULE_ALIAS)))
                    .extracting(PlatformModuleAction::getSourceType)
                    .containsOnly(ModuleActionSourceType.DYNAMIC_MODULE);
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
                    .findFirst().orElseThrow();
            assertThat(draft.getRevisionNo()).isGreaterThan(published.getRevisionNo());
            assertThat(draft.getUiTreeJson()).isEqualTo(published.getUiTreeJson());
            assertThat(published.getUiTreeJson()).contains("searchPlaceholder", "participants", "studentNo")
                    .doesNotContain("\"studentIdTitle\"");

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
                        .containsExactly("studentId", "studentNo", "score", "attendanceStatus");
                assertThat(participants.queryContract().listProjection().fields())
                        .filteredOn(field -> field.fieldName().equals("studentId") || field.fieldName().equals("studentNo")
                                || field.fieldName().equals("attendanceStatus"))
                        .extracting(field -> field.title())
                        .containsExactly("学生", "学号", "参加状态");
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
            nextDraft.setRevisionNo(published.getRevisionNo() + 1);
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
    void shouldReplaceMemberRowsAndCascadeSoftDeleteWhenClassroomIsDeleted() {
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
