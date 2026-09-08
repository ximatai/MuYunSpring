package net.ximatai.muyun.spring.platform.web;

/** Frozen module-local HTTP delivery contract, compiled before a page becomes executable. */
public record PageActionInvocation(String method, String path, Input input) {
    public enum Input { NONE, FORM_RECORD }

    public PageActionInvocation {
        if (method == null || !java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("unsupported page action HTTP method: " + method);
        }
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("?") || path.contains("#") || path.contains("..")
                || path.contains("*") || path.contains("\\")
                || path.replace("{recordId}", "").contains("{") || path.replace("{recordId}", "").contains("}")
                || path.indexOf("{recordId}") != path.lastIndexOf("{recordId}")) {
            throw new IllegalArgumentException("invalid module-local page action path: " + path);
        }
        if (input == Input.FORM_RECORD && (!"POST".equals(method) || path.contains("{recordId}"))) {
            throw new IllegalArgumentException("form page action requires POST without a record-id path variable");
        }
        if (input == null) throw new IllegalArgumentException("page action input must not be null");
    }
}
