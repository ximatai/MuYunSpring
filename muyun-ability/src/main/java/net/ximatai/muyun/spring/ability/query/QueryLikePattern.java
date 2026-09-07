package net.ximatai.muyun.spring.ability.query;

/** Literal substring matching for user-entered search text, distinct from a raw SQL LIKE pattern. */
public final class QueryLikePattern {
    private QueryLikePattern() {
    }

    public static String containsLiteral(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
