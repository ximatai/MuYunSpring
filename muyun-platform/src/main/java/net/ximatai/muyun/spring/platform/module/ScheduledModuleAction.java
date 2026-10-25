package net.ximatai.muyun.spring.platform.module;

import java.util.Map;

/** Domain contribution for a headless module action. Invoke normal domain service methods;
 * scoped reads must use their action-aware query API. No controller or form binding is involved. */
public interface ScheduledModuleAction {
    String moduleAlias();
    String actionCode();
    default void validate(Map<String, Object> parameters) { }
    Object execute(Map<String, Object> parameters);
}
