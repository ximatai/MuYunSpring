package net.ximatai.muyun.spring.iam.welink;

import java.net.URI;
import java.util.*;

/** Validated per-provider vendor settings. Values are never echoed in validation errors. */
final class WeLinkConfiguration {
    static final String LOGIN = "https://login.welink.huaweicloud.com";
    static final String API = "https://open.welink.huaweicloud.com";
    static final String SCRIPT_PATH = "/sso-proxy-front/public/qrcode/0.0.1/wlQrcodeLogin.js";
    static final String AUTHORIZE_PATH = "/sso/oauth2/sns_authorize";
    static final Set<String> AUTHORITY_KEYS = Set.of("apiBaseUrl", "loginBaseUrl");
    private static final Set<String> KEYS = Set.of("apiBaseUrl", "loginBaseUrl", "scope", "tokenParameterMode",
            "timeoutMillis", "pageSize", "maxPages", "maxSnapshotRows", "minRequestIntervalMillis", "refreshAheadSeconds",
            "backoffMillis", "rateLimitRetries", "rateLimitCodes", "authenticationFailureCodes",
            "noMoreValue", "rootParentCodes", "activeStatuses", "disabledStatuses", "departedStatuses");
    private final Map<String, String> values;

    WeLinkConfiguration(Map<String, String> values) {
        this.values = Map.copyOf(values);
        if (!KEYS.containsAll(values.keySet())) throw invalid("unknown setting");
        base("apiBaseUrl", API);
        String login = base("loginBaseUrl", LOGIN);
        if (!login.equals(LOGIN) && !"http".equals(URI.create(login).getScheme())) {
            throw invalid("loginBaseUrl must use the official origin (HTTP loopback is for protocol tests only)");
        }
        if (!scope().equals("snsapi_login")) throw invalid("scope must be snsapi_login");
        if (!Set.of("query", "form").contains(tokenMode())) throw invalid("tokenParameterMode");
        number("timeoutMillis", 10000, 1, 120000);
        number("pageSize", 100, 1, 200);
        number("maxPages", 10000, 1, 100000);
        maxSnapshotRows();
        number("minRequestIntervalMillis", 0, 0, 60000);
        number("refreshAheadSeconds", 300, 0, 3600);
        number("backoffMillis", 1000, 0, 60000);
        number("rateLimitRetries", 2, 0, 5);
        if (csv("rateLimitCodes", "").contains("0") || csv("authenticationFailureCodes", "401,403").contains("0")) {
            throw invalid("success code cannot be retried");
        }
        csv("rootParentCodes", "");
        var statuses = new HashSet<String>();
        for (String key : List.of("activeStatuses", "disabledStatuses", "departedStatuses")) {
            for (String status : csv(key, key.equals("activeStatuses") ? "3" : "")) {
                if (!statuses.add(status)) throw invalid("overlapping status mapping");
            }
        }
        if (value("noMoreValue", "1").isBlank()) throw invalid("noMoreValue");
    }
    String value(String key, String fallback) { return values.getOrDefault(key, fallback); }
    int number(String key, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(value(key, Integer.toString(fallback)));
            if (n < min || n > max) throw invalid(key);
            return n;
        } catch (NumberFormatException bad) { throw invalid(key); }
    }
    int timeout() { return number("timeoutMillis", 10000, 1, 120000); }
    int pageSize() { return number("pageSize", 100, 1, 200); }
    int maxPages() { return number("maxPages", 10000, 1, 100000); }
    int maxSnapshotRows() { return number("maxSnapshotRows", 100000, 1, 100000); }
    String scope() { return value("scope", "snsapi_login"); }
    String tokenMode() { return value("tokenParameterMode", "query"); }
    Set<String> csv(String key, String fallback) {
        String value = value(key, fallback);
        if (value.isEmpty()) return Set.of();
        var result = new HashSet<String>();
        for (String item : value.split(",", -1)) {
            if (item.isBlank() || !result.add(item.trim())) throw invalid(key);
        }
        return Set.copyOf(result);
    }
    String status(String raw) {
        if (csv("activeStatuses", "3").contains(raw)) return "active";
        if (csv("departedStatuses", "").contains(raw)) return "departed";
        if (csv("disabledStatuses", "").contains(raw)) return "disabled";
        return "unknown";
    }
    URI api(String path) { return URI.create(base("apiBaseUrl", API) + path); }
    String loginBase() { return base("loginBaseUrl", LOGIN); }
    private String base(String key, String fallback) {
        try {
            URI uri = URI.create(value(key, fallback));
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))) throw invalid(key);
            return uri.toString().replaceAll("/$", "");
        } catch (IllegalArgumentException bad) { throw invalid(key); }
    }
    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Invalid WeLink configuration: " + key);
    }
}
