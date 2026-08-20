package net.ximatai.muyun.spring.ability.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Facts already compiled from a static service and supplied to a capability operation facet. */
public record StaticCapabilityOperationContext(Object service,
                                               Set<EntityCapability> capabilities,
                                               Map<net.ximatai.muyun.spring.common.platform.PlatformAction, Method> operationMethods) {
    public boolean supports(EntityCapability capability) {
        return capabilities.contains(capability);
    }
}
