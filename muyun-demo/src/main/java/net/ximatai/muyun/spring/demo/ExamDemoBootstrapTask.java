package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMember;
import net.ximatai.muyun.spring.demo.school.classroom.Classroom;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.demo.school.student.Student;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategory;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategoryService;
import net.ximatai.muyun.spring.demo.school.teacher.Teacher;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.ability.TreeAbility;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Creates a small dynamic aggregate beside the static education student master data. */
public class ExamDemoBootstrapTask implements PlatformBootstrapTask {
    public static final String MODULE_ALIAS = "education.exam";
    private static final String EXAM_METADATA_ALIAS = "exam";
    private static final String PARTICIPANT_METADATA_ALIAS = "exam_participant";
    private static final String PARTICIPANT_RELATION_ALIAS = "participants";
    private static final PageRequest ONE = new PageRequest(0, 1);

    private final PlatformModuleService moduleService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldReferenceConfigService referenceConfigService;
    private final ModuleMetadataRelationService relationService;
    private final DynamicRecordService recordService;
    private final StudentService studentService;
    private final SubjectCategoryService subjectCategoryService;
    private final TeacherService teacherService;
    private final ClassroomService classroomService;
    private final PlatformDynamicRuntimeRefreshService runtimeRefreshService;
    private final TransactionTemplate transactionTemplate;

    public ExamDemoBootstrapTask(PlatformModuleService moduleService,
                                 MetadataService metadataService,
                                 MetadataFieldService fieldService,
                                 MetadataFieldReferenceConfigService referenceConfigService,
                                 ModuleMetadataRelationService relationService,
                                 DynamicRecordService recordService,
                                 StudentService studentService,
                                 SubjectCategoryService subjectCategoryService,
                                 TeacherService teacherService,
                                 ClassroomService classroomService,
                                 PlatformDynamicRuntimeRefreshService runtimeRefreshService,
                                 TransactionTemplate transactionTemplate) {
        this.moduleService = moduleService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.referenceConfigService = referenceConfigService;
        this.relationService = relationService;
        this.recordService = recordService;
        this.studentService = studentService;
        this.subjectCategoryService = subjectCategoryService;
        this.teacherService = teacherService;
        this.classroomService = classroomService;
        this.runtimeRefreshService = runtimeRefreshService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public String name() {
        return "demo-academic-evaluation-metadata";
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public void run() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("demo-exam-bootstrap", "Exam Demo Bootstrap"))) {
            try (TenantContext.Scope ignoredTenant = TenantContext.system("configure exam demo metadata")) {
                // 元数据、字段和关系在同一事务内提交，schema ensure 在事务提交后按完整实体一次建表，
                // 避免逐条保存时向已存在表追加 NOT NULL 列被严格迁移拒绝。
                transactionTemplate.executeWithoutResult(status -> configureMetadata());
                // 元数据事务提交后刷新动态运行态：编译模块定义、建表并注册到内存注册表，
                // 否则后续创建记录时注册表仍不认识该模块；重启场景同样需要该刷新。
                runtimeRefreshService.refresh(MODULE_ALIAS);
            }
            try (TenantContext.Scope ignoredTenant = TenantContext.use(DemoBootstrapTask.TENANT_ALIAS)) {
                Student firstStudent = ensureStudent("demo_student_1001", "S2026001", "陈晨", "高一");
                Student secondStudent = ensureStudent("demo_student_1002", "S2026002", "林晓", "高一");
                SubjectCategory mathematics = ensureSubjectCategory("demo_subject_mathematics", "mathematics", "数学",
                        TreeAbility.ROOT_ID);
                SubjectCategory english = ensureSubjectCategory("demo_subject_english", "english", "英语",
                        TreeAbility.ROOT_ID);
                Teacher mathematicsTeacher = ensureTeacher("demo_teacher_1001", "T2026001", "王老师",
                        mathematics.getId(), firstStudent.getId());
                Classroom classroom = ensureClassroom("demo_classroom_g1a", "G1-A", "高一（1）班", "2026",
                        mathematicsTeacher.getId(), firstStudent.getId(), secondStudent.getId());
                ensureExamRecords(classroom, mathematics, english, firstStudent, secondStudent);
            }
        }
    }

    private void configureMetadata() {
        ensureModule();
        Metadata exam = ensureMetadata(EXAM_METADATA_ALIAS, "考试", "education_exam");
        ensureField(exam.getId(), "title", "title", "string", "考试名称", true, true);
        MetadataField classroomId = ensureField(exam.getId(), "classroomId", "classroom_id", "string", "教学班", true,
                false);
        MetadataField subjectCategoryId = ensureField(exam.getId(), "subjectCategoryId", "subject_category_id", "string",
                "学科", true, false);
        ensureField(exam.getId(), "examDate", "exam_date", "date", "考试日期", true, false);
        ModuleMetadataRelation main = ensureMainRelation(exam.getId());
        ensureStaticReference(classroomId, main, ClassroomService.MODULE_ALIAS, "classCode:classroomCode");
        ensureStaticReference(subjectCategoryId, main, SubjectCategoryService.MODULE_ALIAS, "code:subjectCategoryCode");

        Metadata participant = ensureMetadata(PARTICIPANT_METADATA_ALIAS, "参考学生", "education_exam_participant");
        ensureField(participant.getId(), "examId", "exam_id", "string", "考试", true, false);
        MetadataField studentId = ensureField(participant.getId(), "studentId", "student_id", "string", "学生", true,
                false);
        ensureField(participant.getId(), "score", "score", "decimal", "成绩", false, false);
        ensureField(participant.getId(), "attendanceStatus", "attendance_status", "string", "参考状态", true, false);
        ModuleMetadataRelation participants = ensureChildRelation(participant.getId(), main.getMetadataId());
        removeLegacyChildField(participant.getId(), "studentNo");
        removeLegacyChildField(participant.getId(), "studentName");
        ensureStaticReference(studentId, participants, StudentService.MODULE_ALIAS, "studentNo:studentNo,title:studentTitle");
    }

    private void ensureModule() {
        if (moduleService.select(MODULE_ALIAS) != null) {
            return;
        }
        PlatformModule module = new PlatformModule();
        module.setAlias(MODULE_ALIAS);
        module.setApplicationAlias("education");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle("考试管理");
        moduleService.insert(module);
    }

    private Metadata ensureMetadata(String alias, String title, String tableName) {
        Metadata existing = metadataService.list(Criteria.of()
                .eq("applicationAlias", "education")
                .eq("alias", alias), ONE).stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias("education");
        metadata.setAlias(alias);
        metadata.setTitle(title);
        metadata.setTableName(tableName);
        String id = metadataService.insert(metadata);
        return metadataService.select(id);
    }

    private MetadataField ensureField(String metadataId, String fieldName, String columnName, String fieldSpecAlias,
                                      String title, boolean required, boolean titleField) {
        MetadataField existing = fieldService.list(Criteria.of().eq("metadataId", metadataId).eq("fieldName", fieldName), ONE)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        MetadataField field = new MetadataField();
        field.setMetadataId(metadataId);
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(fieldSpecAlias);
        field.setTitle(title);
        field.setRequired(required);
        field.setTitleField(titleField);
        return fieldService.select(fieldService.insert(field));
    }

    private ModuleMetadataRelation ensureMainRelation(String metadataId) {
        ModuleMetadataRelation existing = relationService.list(Criteria.of()
                .eq("moduleAlias", MODULE_ALIAS)
                .eq("relationRole", RelationRole.MAIN), ONE).stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(MODULE_ALIAS);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias(EXAM_METADATA_ALIAS);
        relation.setTitle("考试");
        String id = relationService.insert(relation);
        return relationService.select(id);
    }

    private ModuleMetadataRelation ensureChildRelation(String metadataId, String parentMetadataId) {
        ModuleMetadataRelation existing = relationService.list(Criteria.of().eq("moduleAlias", MODULE_ALIAS)
                .eq("metadataId", metadataId).eq("relationRole", RelationRole.CHILD), ONE)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(MODULE_ALIAS);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.CHILD);
        relation.setRelationAlias(PARTICIPANT_RELATION_ALIAS);
        relation.setTitle("参考学生");
        relation.setParentMetadataId(parentMetadataId);
        relation.setForeignKey("examId");
        relation.setAutoPopulate(Boolean.TRUE);
        return relationService.select(relationService.insert(relation));
    }

    private void ensureStaticReference(MetadataField field,
                                       ModuleMetadataRelation relation,
                                       String targetModuleAlias,
                                       String projectionMappings) {
        if (referenceConfigService.findForRelation(field.getId(), relation.getId()) != null) {
            return;
        }
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setMetadataFieldId(field.getId());
        config.setRelationId(relation.getId());
        config.setTargetModuleAlias(targetModuleAlias);
        config.setProjectionMappings(projectionMappings);
        referenceConfigService.insert(config);
    }

    /**
     * 参考学生的学号和姓名曾是复制字段。它们不是考试事实，当前版本只保留学生引用及其读投影。
     * 子表字段不属于动态 MAIN 的受治理变更面，因此可在 bootstrap 内直接回收。
     */
    private void removeLegacyChildField(String metadataId, String fieldName) {
        fieldService.list(Criteria.of().eq("metadataId", metadataId).eq("fieldName", fieldName), ONE)
                .stream().findFirst().ifPresent(field -> fieldService.delete(field.getId()));
    }

    private Student ensureStudent(String id, String studentNo, String title, String grade) {
        Student existing = studentService.selectIgnoreSoftDelete(id);
        if (existing != null) {
            return existing;
        }
        Student student = new Student();
        student.setId(id);
        student.setStudentNo(studentNo);
        student.setTitle(title);
        student.setGrade(grade);
        student.setEnabled(Boolean.TRUE);
        studentService.insert(student);
        return student;
    }

    private SubjectCategory ensureSubjectCategory(String id, String code, String title, String parentId) {
        SubjectCategory existing = subjectCategoryService.selectIgnoreSoftDelete(id);
        if (existing != null) return existing;
        SubjectCategory category = new SubjectCategory();
        category.setId(id);
        category.setCode(code);
        category.setTitle(title);
        category.setParentId(parentId);
        category.setEnabled(Boolean.TRUE);
        subjectCategoryService.insert(category);
        return category;
    }

    private Teacher ensureTeacher(String id,
                                  String teacherNo,
                                  String title,
                                  String subjectCategoryId,
                                  String studentAssistantId) {
        Teacher existing = teacherService.selectIgnoreSoftDelete(id);
        if (existing != null) return existing;
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setTeacherNo(teacherNo);
        teacher.setTitle(title);
        teacher.setSubjectCategoryId(subjectCategoryId);
        teacher.setStudentAssistantId(studentAssistantId);
        teacher.setEnabled(Boolean.TRUE);
        teacherService.insert(teacher);
        return teacher;
    }

    private Classroom ensureClassroom(String id,
                                      String classCode,
                                      String title,
                                      String academicYear,
                                      String homeroomTeacherId,
                                      String... studentIds) {
        Classroom existing = classroomService.selectIgnoreSoftDelete(id);
        if (existing != null) return existing;
        Classroom classroom = new Classroom();
        classroom.setId(id);
        classroom.setClassCode(classCode);
        classroom.setTitle(title);
        classroom.setAcademicYear(academicYear);
        classroom.setHomeroomTeacherId(homeroomTeacherId);
        classroom.setMembers(java.util.Arrays.stream(studentIds).map(studentId -> {
            ClassMember member = new ClassMember();
            member.setStudentId(studentId);
            return member;
        }).toList());
        classroomService.insert(classroom);
        return classroom;
    }

    private void ensureExamRecords(Classroom classroom,
                                   SubjectCategory mathematics,
                                   SubjectCategory english,
                                   Student firstStudent,
                                   Student secondStudent) {
        if (recordService.mainEntity(MODULE_ALIAS).count(Criteria.of().eq("title", "2026 春季期中数学测评")) > 0) {
            return;
        }
        DynamicRecord firstExam = recordService.newRecord(MODULE_ALIAS, EXAM_METADATA_ALIAS)
                .setValue("title", "2026 春季期中数学测评")
                .setValue("classroomId", classroom.getId())
                .setValue("subjectCategoryId", mathematics.getId())
                .setValue("examDate", LocalDate.of(2026, 4, 18));
        firstExam.setChildren(PARTICIPANT_RELATION_ALIAS, List.of(
                participant(firstStudent, new BigDecimal("92.5"), "ATTENDED"),
                participant(secondStudent, new BigDecimal("86"), "ATTENDED")));
        recordService.mainEntity(MODULE_ALIAS).create(firstExam);

        DynamicRecord secondExam = recordService.newRecord(MODULE_ALIAS, EXAM_METADATA_ALIAS)
                .setValue("title", "2026 春季英语听力测试")
                .setValue("classroomId", classroom.getId())
                .setValue("subjectCategoryId", english.getId())
                .setValue("examDate", LocalDate.of(2026, 5, 8));
        secondExam.setChildren(PARTICIPANT_RELATION_ALIAS, List.of(
                participant(firstStudent, new BigDecimal("95"), "ATTENDED"),
                participant(secondStudent, null, "ABSENT")));
        recordService.mainEntity(MODULE_ALIAS).create(secondExam);
    }

    private DynamicRecord participant(Student student, BigDecimal score, String attendanceStatus) {
        DynamicRecord participant = recordService.newRecord(MODULE_ALIAS, PARTICIPANT_METADATA_ALIAS)
                .setValue("studentId", student.getId())
                .setValue("attendanceStatus", attendanceStatus);
        if (score != null) {
            participant.setValue("score", score);
        }
        return participant;
    }
}
