package net.ximatai.muyun.spring.demo.school.purchase;
import net.ximatai.muyun.spring.demo.school.configuration.EducationApplication;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = PurchaseRequestService.MODULE_ALIAS, title = "采购申请", capabilities = net.ximatai.muyun.spring.common.platform.EntityCapability.APPROVAL)
@PlatformMenu(parent = TeachingDemoMenuGroups.ROOT, title = "采购审批演示", order = 230)
@RequestMapping("/" + PurchaseRequestService.MODULE_ALIAS)
public class PurchaseRequestWebController extends WebSupport<PurchaseRequestService>
        implements CrudWeb<PurchaseRequest, PurchaseRequestService>, StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PurchaseRequestService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("采购申请").titleField("title").secondaryField("approvalStatus"))
                        .detail(detail -> detail.editor(form -> form.title("采购申请")
                                .field("title", field -> field.label("申请名称").required())
                                .field("amount", field -> field.label("采购金额"))
                                .field("delivered", field -> field.label("已到货"))
                                .field("remark", field -> field.label("备注"))))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()))))
                .build();
    }
}
