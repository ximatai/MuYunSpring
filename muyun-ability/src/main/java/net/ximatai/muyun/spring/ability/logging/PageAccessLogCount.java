package net.ximatai.muyun.spring.ability.logging;

/** One stable page-entry key and its observed count. */
public record PageAccessLogCount(String pageKey, long accessCount) {
    public PageAccessLogCount {
        pageKey = BusinessLogContext.required(pageKey, "pageKey", 256);
        if (accessCount < 0) {
            throw new IllegalArgumentException("accessCount must not be negative");
        }
    }
}
