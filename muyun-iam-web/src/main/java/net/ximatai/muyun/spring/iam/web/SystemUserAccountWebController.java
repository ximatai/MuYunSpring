package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.system_user", title = "系统账号管理",
        route = "/iam/system-user")
@PlatformMenu(id = UserAccountWebController.SYSTEM_USER_MENU_ID, parent = PlatformMenuGroups.IDENTITY,
        moduleAlias = "iam.user", order = 65)
public class SystemUserAccountWebController {
}
