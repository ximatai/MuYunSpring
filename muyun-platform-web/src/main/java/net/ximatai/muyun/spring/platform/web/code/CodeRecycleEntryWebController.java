package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.code.CodeRecycleEntry;
import net.ximatai.muyun.spring.platform.code.CodeRecycleEntryService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeRecycleEntryService.MODULE_ALIAS, title = "编码回收")
@RequestMapping({"/platform.code_recycle_entry", "/platform/code/recycle-entry"})
public class CodeRecycleEntryWebController extends WebSupport<CodeRecycleEntryService> implements
        QueryViewWeb<CodeRecycleEntry, CodeRecycleEntryService> {
}
