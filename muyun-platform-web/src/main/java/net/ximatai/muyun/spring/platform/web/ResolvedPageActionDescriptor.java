package net.ximatai.muyun.spring.platform.web;

/** Source-neutral action placement consumed by a platform-owned page region. */
public record ResolvedPageActionDescriptor(String actionCode, PageActionAnchor anchor) {
    public ResolvedPageActionDescriptor {
        if (actionCode == null || actionCode.isBlank()) throw new IllegalArgumentException("page action code must not be blank");
        actionCode = actionCode.trim();
        if (anchor == null) throw new IllegalArgumentException("page action anchor must not be null");
    }
}
