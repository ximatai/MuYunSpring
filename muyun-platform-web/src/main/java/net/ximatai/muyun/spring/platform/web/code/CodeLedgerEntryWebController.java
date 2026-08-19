package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.code.CodeLedgerEntry;
import net.ximatai.muyun.spring.platform.code.CodeLedgerEntryService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeLedgerEntryService.MODULE_ALIAS, title = "编码台账")
@RequestMapping({"/platform.code_ledger_entry", "/platform/code/ledger-entry"})
public class CodeLedgerEntryWebController extends WebSupport<CodeLedgerEntryService> implements
        QueryViewWeb<CodeLedgerEntry, CodeLedgerEntryService> {
}
