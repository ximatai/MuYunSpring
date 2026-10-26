package net.ximatai.muyun.spring.platform.module;

import java.util.Map;

/** Domain contribution for a headless module action. Invoke normal domain service methods;
 * scoped reads must use their action-aware query API. No controller or form binding is involved. */
public interface ScheduledModuleAction {
    String moduleAlias();
    String actionCode();
    /** Declares whether the action requires a non-empty parameter object; validate remains authoritative. */
    default boolean parametersRequired() { return false; }
    default void validate(Map<String, Object> parameters) { }
    Object execute(Map<String, Object> parameters);
}
