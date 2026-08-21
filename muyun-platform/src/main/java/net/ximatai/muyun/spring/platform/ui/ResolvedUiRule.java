package net.ximatai.muyun.spring.platform.ui;

/** Source-neutral conditional UI rule shared by fields, groups and relation surfaces. */
public record ResolvedUiRule<T>(T constant, ResolvedUiFormula formula) {
    public static <T> ResolvedUiRule<T> constant(T value) {
        return new ResolvedUiRule<>(value, null);
    }
}
