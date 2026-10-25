package net.ximatai.muyun.spring.platform.job;

import java.util.*;

public final class JobRegistry {
    private final Map<String, JobHandler> handlers;
    private final Map<String, JobDefinition> definitions;
    public JobRegistry(List<JobHandler> handlers) {
        Map<String, JobHandler> values = new LinkedHashMap<>();
        Map<String, JobDefinition> types = new LinkedHashMap<>();
        for (JobHandler handler : handlers) {
            var type = Objects.requireNonNull(handler.definition());
            if (values.putIfAbsent(type.code(), handler) != null) throw new IllegalArgumentException("Duplicate job type: " + type.code());
            types.put(type.code(), type);
        }
        this.handlers = Collections.unmodifiableMap(values); this.definitions = Collections.unmodifiableMap(types);
    }
    public Collection<JobDefinition> definitions() { return definitions.values(); }
    public JobDefinition definition(String code) {
        var type = definitions.get(code);
        if (type == null) throw new IllegalArgumentException("Unregistered job type: " + code);
        return type;
    }
    public boolean contains(String code) { return definitions.containsKey(code); }
    public JobHandler handler(String code) { definition(code); return handlers.get(code); }
}
