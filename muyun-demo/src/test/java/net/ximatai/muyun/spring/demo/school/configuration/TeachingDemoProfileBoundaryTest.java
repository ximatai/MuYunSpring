package net.ximatai.muyun.spring.demo.school.configuration;

import net.ximatai.muyun.spring.demo.school.classroom.ClassMemberService;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategoryService;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TeachingDemoProfileBoundaryTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DemoPackageScan.class);

    @Test
    void shouldNotRegisterSchoolComponentsWithoutSchoolDemoProfile() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(StudentService.class);
            assertThat(context).doesNotHaveBean(TeacherService.class);
            assertThat(context).doesNotHaveBean(ClassroomService.class);
            assertThat(context).doesNotHaveBean(ClassMemberService.class);
            assertThat(context).doesNotHaveBean(SubjectCategoryService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "net.ximatai.muyun.spring.demo.school")
    static class DemoPackageScan {
    }
}
