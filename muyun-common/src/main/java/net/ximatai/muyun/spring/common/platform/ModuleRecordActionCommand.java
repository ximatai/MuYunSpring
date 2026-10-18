package net.ximatai.muyun.spring.common.platform;

import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source neutral business action input. Workflow identity is owned by its caller, never by the payload. */
public record ModuleRecordActionCommand(String moduleAlias, String recordId, String actionCode,
                                       Integer version, Map<String, Object> values, Map<String, Object> payload,
                                       Map<String, SourceInput> contentLanguages) {
    public ModuleRecordActionCommand {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        contentLanguages = contentLanguages == null ? Map.of() : Map.copyOf(contentLanguages);
        if (!values.keySet().containsAll(contentLanguages.keySet()))
            throw new IllegalArgumentException("Source languages require explicitly submitted business fields");
    }
    public ModuleRecordActionCommand(String moduleAlias, String recordId, String actionCode, Integer version,
                                     Map<String, Object> values, Map<String, Object> payload) {
        this(moduleAlias, recordId, actionCode, version, values, payload, Map.of());
    }
}
