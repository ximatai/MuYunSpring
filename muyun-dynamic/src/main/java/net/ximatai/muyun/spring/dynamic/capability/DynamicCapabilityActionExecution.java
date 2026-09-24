package net.ximatai.muyun.spring.dynamic.capability;

import java.util.List;

/** Capability mutations bound by the action runtime to one module, entity and action trace. */
public interface DynamicCapabilityActionExecution {
    int enable(String id);
    int disable(String id);
    void reorder(List<String> orderedIds);
    void moveBefore(String id, String beforeId);
    void moveAfter(String id, String afterId);
}
