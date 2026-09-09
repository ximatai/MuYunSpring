package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;

/** Closed, source-specific execution port implemented by the static web runtime. */
public interface StaticCapabilityActionExecution {
    Object executeEnable(PlatformAction action);

    Object executeSort();

    default Object executePermissions() { throw new UnsupportedOperationException("permissions runtime is not configured"); }

    Object executeTree(PlatformAction action);

    Object executeRecycleBin(PlatformAction action);
}
