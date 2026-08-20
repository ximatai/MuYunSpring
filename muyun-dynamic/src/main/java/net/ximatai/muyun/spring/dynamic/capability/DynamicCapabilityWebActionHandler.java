package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;

/** Closed dynamic-web execution facet owned by a capability action contribution. */
@FunctionalInterface
public interface DynamicCapabilityWebActionHandler {
    int execute(DynamicCapabilityWebActionExecution execution,
                PlatformAction action,
                DynamicCapabilityWebSortRequest request);
}
