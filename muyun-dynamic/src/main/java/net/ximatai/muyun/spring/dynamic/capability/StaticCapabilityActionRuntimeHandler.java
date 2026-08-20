package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.PlatformAction;

/**
 * Static-source execution facet owned by one capability module.
 *
 * <p>The web runtime supplies the typed static operation port; modules select only their own
 * semantic operation. This remains a closed platform composition boundary, not a plugin SPI.</p>
 */
@FunctionalInterface
public interface StaticCapabilityActionRuntimeHandler {
    Object execute(StaticCapabilityActionExecution execution, PlatformAction action);
}
