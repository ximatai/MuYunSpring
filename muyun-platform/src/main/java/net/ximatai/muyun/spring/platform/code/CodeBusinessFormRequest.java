package net.ximatai.muyun.spring.platform.code;
import java.util.*;
/** Draft input only. It never authorizes a write or asks the platform to reserve a code. */
public record CodeBusinessFormRequest(String entityAlias, Map<String, Object> record, Map<String, Object> parent) {
    public CodeBusinessFormRequest {
        record = record == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(record));
        parent = parent == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parent));
    }
}
