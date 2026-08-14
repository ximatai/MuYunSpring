package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMember;
import net.ximatai.muyun.spring.demo.school.classroom.ClassMemberService;
import net.ximatai.muyun.spring.demo.school.classroom.Classroom;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoConfiguration;
import net.ximatai.muyun.spring.demo.school.hobby.Hobby;
import net.ximatai.muyun.spring.demo.school.hobby.HobbyService;
import net.ximatai.muyun.spring.demo.school.student.Student;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.teacher.Teacher;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态业务模块的最终交付演示：在真实 Boot 上下文中验证 Spring 装配、Repository 持久化、
 * 自动建表、静态模块/端点注册和业务 Ability 组合。
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
    private HobbyService hobbies;

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
                        "education.classroom.recycleBin.restore", "education.hobby.tree.tree",
                        "education.hobby.tree.sort");
    }

    @Test
    void shouldSupportTreeHobbiesAndResolveStudentMultiSelectTitles() {
        String serial = serial();
        try (TenantContext.Scope ignored = TenantContext.use("campus-hobby")) {
            String sportId = hobbies.insert(hobby("sport-" + serial, "运动", TreeAbility.ROOT_ID));
            String basketballId = hobbies.insert(hobby("basketball-" + serial, "篮球", sportId));
            String readingId = hobbies.insert(hobby("reading-" + serial, "阅读", TreeAbility.ROOT_ID));
            Student student = student("S-" + serial, "爱好学生", "五年级");
            student.setHobbyIds(new LinkedHashSet<>(List.of(basketballId, readingId)));
            String studentId = students.insert(student);

            assertThat(hobbies.children(sportId)).extracting(Hobby::getId).containsExactly(basketballId);
            Student selected = students.select(studentId);
            assertThat(selected.getHobbyIds()).containsExactlyInAnyOrder(basketballId, readingId);
            assertThat(selected.getHobbyTitles()).containsExactlyInAnyOrder("篮球", "阅读");

            Hobby basketball = hobbies.select(basketballId);
            basketball.setTitle("篮球校队");
            assertThat(hobbies.update(basketball)).isEqualTo(1);
            assertThat(students.select(studentId).getHobbyTitles())
                    .containsExactlyInAnyOrder("篮球校队", "阅读");
        }
    }


    @Test
    void shouldResolveHomeroomTeacherAndPopulateClassMembers() {
        try (TenantContext.Scope ignored = TenantContext.system("school demo aggregate")) {
            String assistantId = students.insert(student("S-" + serial(), "李同学", "二年级"));
            Teacher homeroomTeacher = teacher("T-" + serial(), "王老师", "mathematics");
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
            String teacherId = teachers.insert(teacher("T-" + serial(), "王老师", "mathematics"));
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

    private Hobby hobby(String code, String title, String parentId) {
        Hobby hobby = new Hobby();
        hobby.setCode(code);
        hobby.setTitle(title);
        hobby.setParentId(parentId);
        return hobby;
    }

    private Student student(String studentNo, String title, String grade) {
        Student student = new Student();
        student.setStudentNo(studentNo);
        student.setTitle(title);
        student.setGrade(grade);
        return student;
    }

    private Teacher teacher(String teacherNo, String title, String subjectCode) {
        Teacher teacher = new Teacher();
        teacher.setTeacherNo(teacherNo);
        teacher.setTitle(title);
        teacher.setSubjectCode(subjectCode);
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

    private String serial() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
