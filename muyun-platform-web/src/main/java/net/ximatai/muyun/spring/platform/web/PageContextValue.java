package net.ximatai.muyun.spring.platform.web;

/**
 * A page-context value with an explicit presence bit.
 *
 * <p>A present {@code null} is meaningful for a trusted selection such as the platform role
 * range, which must clear a nullable owner field. It must not be conflated with an unresolved
 * field.</p>
 */
public record PageContextValue(boolean present, Object value) {
    public static PageContextValue absent() {
        return new PageContextValue(false, null);
    }

    public static PageContextValue of(Object value) {
        return new PageContextValue(true, value);
    }
}
