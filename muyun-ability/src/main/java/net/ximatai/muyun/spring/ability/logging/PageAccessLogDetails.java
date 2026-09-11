package net.ximatai.muyun.spring.ability.logging;

/** Typed details for one declared page-entry fact. */
public record PageAccessLogDetails(
        String pageKey,
        String pageId,
        String menuId,
        String entrySource
) implements BusinessLogDetails {
    /** Compatibility constructor for callers that only have a stable page key. */
    public PageAccessLogDetails(String pageKey, String menuId, String entrySource) {
        this(pageKey, null, menuId, entrySource);
    }

    public PageAccessLogDetails {
        pageKey = BusinessLogContext.required(pageKey, "pageKey", 256);
        pageId = BusinessLogContext.optional(pageId, "pageId", 256);
        menuId = BusinessLogContext.optional(menuId, "menuId", 128);
        entrySource = BusinessLogContext.optional(entrySource, "entrySource", 64);
    }
}
