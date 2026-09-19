package net.ximatai.muyun.spring.platform.web;

/** Source-neutral action placement consumed by a platform-owned page region. */
public record ResolvedPageActionDescriptor(String actionCode, PageActionAnchor anchor, String title,
                                           PageActionOperation operation, PageActionInvocation invocation,
                                           PageActionStatusMode statusMode) {
    public ResolvedPageActionDescriptor(String actionCode, PageActionAnchor anchor, String title, PageActionOperation operation) {
        this(actionCode, anchor, title, operation, null, PageActionStatusMode.NONE);
    }
    public ResolvedPageActionDescriptor(String actionCode, PageActionAnchor anchor, String title,
                                        PageActionOperation operation, PageActionInvocation invocation) {
        this(actionCode, anchor, title, operation, invocation, PageActionStatusMode.NONE);
    }
    public ResolvedPageActionDescriptor(String actionCode, PageActionAnchor anchor) {
        this(actionCode, anchor, null, null, null, PageActionStatusMode.NONE);
    }
    public ResolvedPageActionDescriptor {
        if (actionCode == null || actionCode.isBlank()) throw new IllegalArgumentException("page action code must not be blank");
        actionCode = actionCode.trim();
        if (anchor == null) throw new IllegalArgumentException("page action anchor must not be null");
        statusMode = statusMode == null ? PageActionStatusMode.NONE : statusMode;
    }
}
