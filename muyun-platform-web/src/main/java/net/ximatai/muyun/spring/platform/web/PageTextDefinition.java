package net.ximatai.muyun.spring.platform.web;

/** Authored copy for a descriptor-owned page region: either literal text or a PAGE_TEXT expression. */
public record PageTextDefinition(String text, String expression) {
    public PageTextDefinition {
        text = normalize(text);
        expression = normalize(expression);
        if ((text == null) == (expression == null)) {
            throw new IllegalArgumentException("page text requires exactly one of text or expression");
        }
    }

    public static PageTextDefinition text(String value) { return new PageTextDefinition(value, null); }
    public static PageTextDefinition expression(String value) { return new PageTextDefinition(null, value); }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
