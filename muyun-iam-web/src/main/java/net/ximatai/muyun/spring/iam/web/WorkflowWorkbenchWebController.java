package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeReadFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchCard;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchQueryRequest;
import net.ximatai.muyun.spring.web.WebListResponse;
import org.springframework.web.bind.annotation.*;

/** A discoverable user workbench; every card is restricted to the authenticated user's participation. */
@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = WorkflowWorkbenchWebController.MODULE_ALIAS, title = "审批工作台", route = "/workflow/workbench")
@PlatformMenu(parent = PlatformMenuGroups.BUSINESS_SUPPORT, title = "审批工作台", order = 20, accessMode = ActionAccessMode.LOGIN_REQUIRED)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/iam.workflow_workbench")
public class WorkflowWorkbenchWebController {
    public static final String MODULE_ALIAS = "iam.workflow_workbench";
    private final WorkflowRuntimeReadFacade runtime;
    public WorkflowWorkbenchWebController(WorkflowRuntimeReadFacade runtime) { this.runtime = runtime; }

    @PostMapping("/query")
    @CustomActionEndpoint(value = "query", title = "查看我的审批任务", level = PlatformActionLevel.LIST, accessMode = ActionAccessMode.LOGIN_REQUIRED, actionAuth = false, dataAuth = false)
    public WebListResponse<WorkflowWorkbenchCard> query() {
        String userId = CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationRequiredException("authenticated user is required")).userId();
        return new WebListResponse<>(runtime.todoCards(userId, new PageRequest(0, 30), WorkflowWorkbenchQueryRequest.empty()));
    }
}
