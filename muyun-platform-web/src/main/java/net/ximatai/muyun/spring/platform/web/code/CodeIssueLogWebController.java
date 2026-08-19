package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.code.CodeIssueLog;
import net.ximatai.muyun.spring.platform.code.CodeIssueLogService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeIssueLogService.MODULE_ALIAS, title = "编码日志")
@RequestMapping({"/platform.code_issue_log", "/platform/code/issue-log"})
public class CodeIssueLogWebController extends WebSupport<CodeIssueLogService> implements
        QueryViewWeb<CodeIssueLog, CodeIssueLogService> {
}
