package net.ximatai.muyun.spring.platform.job;

import java.util.Map;

public record JobResult(String code, Map<String, Long> statistics, boolean partial) {
    public JobResult {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid job result code");
        statistics = statistics == null ? Map.of() : Map.copyOf(statistics);
        if (statistics.size() > 100 || statistics.keySet().stream().anyMatch(key -> !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")))
            throw new IllegalArgumentException("Invalid job statistics");
    }
    public static JobResult success(String code, Map<String, Long> statistics) { return new JobResult(code, statistics, false); }
    public static JobResult partial(String code, Map<String, Long> statistics) { return new JobResult(code, statistics, true); }
}
