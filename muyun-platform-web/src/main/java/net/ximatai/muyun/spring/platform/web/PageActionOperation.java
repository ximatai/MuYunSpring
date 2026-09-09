package net.ximatai.muyun.spring.platform.web;

/** Platform interaction intent; business action authorization remains independent. */
public enum PageActionOperation {
    OPEN_CREATE, OPEN_EDIT, SUBMIT_CREATE, SUBMIT_UPDATE, REFRESH, DELETE, ENABLE, DISABLE, MANAGE_PERMISSIONS, INVOKE;

    public static PageActionOperation resolve(String code, PageActionAnchor anchor) {
        if (anchor == PageActionAnchor.PAGE) return switch (code) {
            case "create" -> OPEN_CREATE;
            case "query" -> REFRESH;
            default -> customOperation(code, anchor);
        };
        if (anchor == PageActionAnchor.FORM) return switch (code) {
            case "create" -> SUBMIT_CREATE;
            case "update" -> SUBMIT_UPDATE;
            default -> customOperation(code, anchor);
        };
        return switch (code) {
            case "update" -> OPEN_EDIT;
            case "delete" -> DELETE;
            case "enable" -> ENABLE;
            case "disable" -> DISABLE;
            case "managePermissions" -> MANAGE_PERMISSIONS;
            default -> customOperation(code, anchor);
        };
    }
    /** Validate source facts independently of the current user's execution permissions. */
    public static void validate(ModuleUiDefinition definition, java.util.function.Function<String, String> scopeOf) {
        if (!definition.managedActions()) return;
        for (var entry : definition.pageActions()) {
            var operation = resolve(entry.actionCode(), entry.anchor());
            String scope = scopeOf.apply(entry.actionCode());
            if (scope == null) throw new IllegalArgumentException("页面动作来源失效：" + entry.actionCode());
            // Form capability is independent of persisted-record scope; publication validates its binding.
            if (entry.anchor() == PageActionAnchor.FORM && operation == INVOKE) continue;
            String expected = entry.anchor() == PageActionAnchor.PAGE
                    || (entry.anchor() == PageActionAnchor.FORM && "create".equals(entry.actionCode()))
                    ? "LIST" : "RECORD";
            if (!expected.equals(scope) && !"ANY".equals(scope)) {
                throw new IllegalArgumentException("动作作用范围不支持此页面区域：" + entry.actionCode());
            }
        }
    }

    private static PageActionOperation customOperation(String code, PageActionAnchor anchor) {
        if (java.util.Set.of("access", "view", "create", "update", "query", "delete", "enable", "disable", "batchDelete", "sort", "tree", "reference").contains(code)) throw unsupported(code, anchor);
        return INVOKE;
    }

    private static IllegalArgumentException unsupported(String code, PageActionAnchor anchor) {
        return new IllegalArgumentException("页面操作入口尚无可用交互契约：" + code + " / " + anchor);
    }
}
