package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

/** Closed dynamic-web port; concrete HTTP types stay outside the capability core. */
public interface DynamicCapabilityWebActionExecution {
    boolean supports(EntityCapability capability);

    void moveBefore(String id, String beforeId);

    void moveAfter(String id, String afterId);

    void moveInTree(String id, String previousId, String nextId, String parentId);
}
