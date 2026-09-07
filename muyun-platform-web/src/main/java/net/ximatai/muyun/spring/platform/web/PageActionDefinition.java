package net.ximatai.muyun.spring.platform.web;

/** One action placement declared by a page composition or the static page DSL. */
public record PageActionDefinition(String actionCode, PageActionAnchor anchor) {
    public PageActionDefinition {
        if (actionCode == null || actionCode.isBlank()) throw new IllegalArgumentException("page action code must not be blank");
        actionCode = actionCode.trim();
        if (anchor == null) throw new IllegalArgumentException("page action anchor must not be null");
    }
}
