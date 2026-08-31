package net.ximatai.muyun.spring.demo.school.subject;

import net.ximatai.muyun.spring.demo.school.configuration.EducationApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

/** 学科分类树的标准 Web 交付入口。 */
@RestController
@Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = SubjectCategoryService.MODULE_ALIAS, title = "学科分类")
@StaticModuleOpenApi
@RequestMapping("/" + SubjectCategoryService.MODULE_ALIAS)
public class SubjectCategoryWebController extends WebSupport<SubjectCategoryService>
        implements CrudWeb<SubjectCategory, SubjectCategoryService> {
}
