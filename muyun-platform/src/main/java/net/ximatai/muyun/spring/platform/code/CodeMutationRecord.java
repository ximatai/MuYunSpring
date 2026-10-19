package net.ximatai.muyun.spring.platform.code;

/** Bounded value adapter; permissions and normal persistence remain with the owning service. */
public interface CodeMutationRecord {
    String getId();
    Object getValue(String field);
    boolean isExplicitlySet(String field);
    void putGeneratedValue(String field, Object value);
    default boolean allowsAutomaticGeneration(String field) { return true; }
    default String normalizeCode(String field, String value) { return value; }
    boolean codeExists(String field, String value);
}
