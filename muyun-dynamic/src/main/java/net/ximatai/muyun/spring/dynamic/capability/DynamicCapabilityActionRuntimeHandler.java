package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;

/** Dynamic-source execution facet owned by one capability module. */
@FunctionalInterface
public interface DynamicCapabilityActionRuntimeHandler {
    int execute(PlatformAction action,
                DynamicRecordService service,
                String moduleAlias,
                String entityAlias,
                DynamicActionExecutionRequest request,
                String traceId);
}
