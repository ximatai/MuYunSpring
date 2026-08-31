package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.DefaultTenantRoleProvisioner;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.demo.school.student.StudentService;
import net.ximatai.muyun.spring.demo.school.subject.SubjectCategoryService;
import net.ximatai.muyun.spring.demo.school.teacher.TeacherService;
import net.ximatai.muyun.spring.demo.school.classroom.ClassroomService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

/** Declares the initial data for the complete school-demo environment. */
@AutoConfiguration
@Profile("school-demo")
@EnableConfigurationProperties(DemoBootstrapProperties.class)
public class DemoBootstrapConfiguration {
    @Bean
    DemoBootstrapTask demoBootstrapTask(DemoBootstrapProperties properties, TenantService tenantService,
                                        OrganizationService organizationService, DepartmentService departmentService,
                                        EmployeeService employeeService, UserAccountService userAccountService,
                                        EmployeeAccountService employeeAccountService,
                                        DefaultTenantRoleProvisioner tenantRoleProvisioner) {
        return new DemoBootstrapTask(properties, tenantService, organizationService, departmentService,
                employeeService, userAccountService, employeeAccountService, tenantRoleProvisioner);
    }

    @Bean
    ExamDemoBootstrapTask examDemoBootstrapTask(PlatformModuleService moduleService,
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
        return new ExamDemoBootstrapTask(moduleService, metadataService, fieldService, referenceConfigService,
                relationService, recordService, studentService, subjectCategoryService, teacherService,
                classroomService, runtimeRefreshService, transactionTemplate);
    }

    @Bean
    ExamPageDemoBootstrapTask examPageDemoBootstrapTask(ModuleMetadataRelationService relationService,
                                                        PlatformPageDefinitionService pageService,
                                                        PlatformPresentationVariantService variantService,
                                                        PlatformPresentationRevisionService revisionService,
                                                        PlatformPresentationRevisionPublishService publishService) {
        return new ExamPageDemoBootstrapTask(relationService, pageService, variantService, revisionService,
                publishService);
    }
}
