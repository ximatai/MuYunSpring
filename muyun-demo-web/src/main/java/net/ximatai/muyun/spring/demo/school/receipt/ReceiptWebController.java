package net.ximatai.muyun.spring.demo.school.receipt;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.demo.school.configuration.EducationApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Intentionally has no menu, page or dynamic metadata declaration. */
@RestController @Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = ReceiptService.MODULE_ALIAS, title = "业务回执")
@RequestMapping("/" + ReceiptService.MODULE_ALIAS)
public class ReceiptWebController extends StaticModuleWebControllerAdapter<ReceiptService>
        implements CrudWeb<Receipt, ReceiptService> {
    public record Confirmation(Integer expectedVersion) { }
    @PostMapping("/{id}/confirm")
    @CustomActionEndpoint(value = "confirm", title = "确认回执", level = PlatformActionLevel.RECORD, dataAuth = true)
    public Receipt confirm(@PathVariable String id, @RequestBody Confirmation input) {
        return webScope(() -> service().confirm(id, input.expectedVersion()));
    }
}
