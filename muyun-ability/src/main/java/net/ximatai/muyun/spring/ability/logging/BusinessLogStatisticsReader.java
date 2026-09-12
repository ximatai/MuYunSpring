package net.ximatai.muyun.spring.ability.logging;

/**
 * Service-facing statistics boundary. Callers must apply tenant and diagnostic-read authorization
 * before constructing a query; this neutral contract intentionally does not expose an HTTP API.
 */
public interface BusinessLogStatisticsReader {
    ActionLogStatistics actionStatistics(BusinessLogStatisticsQuery query);

    PageAccessLogStatistics pageAccessStatistics(BusinessLogStatisticsQuery query);
}
