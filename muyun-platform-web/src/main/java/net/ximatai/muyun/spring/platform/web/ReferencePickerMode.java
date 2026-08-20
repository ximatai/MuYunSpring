package net.ximatai.muyun.spring.platform.web;

/**
 * Server-compiled interaction shape for choosing a reference target.
 *
 * <p>{@link #AUTO} is retained only when the target module cannot be resolved
 * while compiling the descriptor. Page hosts must not infer a mode themselves.</p>
 */
public enum ReferencePickerMode {
    LIST,
    TREE,
    AUTO
}
