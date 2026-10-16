package net.ximatai.muyun.spring.common.platform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source neutral business action input. Workflow identity is owned by its caller, never by the payload. */
public record ModuleRecordActionCommand(String moduleAlias, String recordId, String actionCode,
                                       Integer version, Map<String, Object> values, Map<String, Object> payload) {
    public ModuleRecordActionCommand {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
