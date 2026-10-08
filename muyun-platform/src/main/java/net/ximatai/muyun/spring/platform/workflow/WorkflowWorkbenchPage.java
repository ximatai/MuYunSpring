package net.ximatai.muyun.spring.platform.workflow;

import java.util.Map;
import net.ximatai.muyun.database.core.orm.PageResult;

public record WorkflowWorkbenchPage(PageResult<WorkflowWorkbenchCard> page, Map<String, String> modules) {
    public WorkflowWorkbenchPage { modules = Map.copyOf(modules); }
}
