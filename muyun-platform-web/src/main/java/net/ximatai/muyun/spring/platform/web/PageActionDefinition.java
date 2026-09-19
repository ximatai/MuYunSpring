package net.ximatai.muyun.spring.platform.web;

/** One action placement declared by a page composition or the static page DSL. */
public record PageActionDefinition(String actionCode, PageActionAnchor anchor, String title,
                                   PageActionStatusMode statusMode) {
    public PageActionDefinition(String actionCode, PageActionAnchor anchor) {
        this(actionCode, anchor, null, PageActionStatusMode.NONE);
    }
    public PageActionDefinition(String actionCode, PageActionAnchor anchor, String title) {
        this(actionCode, anchor, title, PageActionStatusMode.NONE);
    }
    public PageActionDefinition(String actionCode, PageActionAnchor anchor, PageActionStatusMode statusMode) {
        this(actionCode, anchor, null, statusMode);
    }
    public String key() { return anchor + ":" + actionCode; }
    public PageActionDefinition {
        if (actionCode == null || actionCode.isBlank()) throw new IllegalArgumentException("page action code must not be blank");
        actionCode = actionCode.trim();
        title = title == null || title.isBlank() ? null : title.trim();
        if (anchor == null) throw new IllegalArgumentException("page action anchor must not be null");
        statusMode = statusMode == null ? PageActionStatusMode.NONE : statusMode;
    }
}
