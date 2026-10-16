package net.ximatai.muyun.spring.platform.workflow;

import java.util.Map;
import net.ximatai.muyun.database.core.orm.PageResult;

public record WorkflowWorkbenchPage(PageResult<WorkflowWorkbenchCard> page, Map<String, String> modules,
        net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog moduleTitles) {
    public WorkflowWorkbenchPage(PageResult<WorkflowWorkbenchCard> page, Map<String, String> modules) {
        this(page, modules, new net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog(Map.of()));
    }
    public WorkflowWorkbenchPage { modules = Map.copyOf(modules); }
}
