package net.ximatai.muyun.spring.platform.web;

/**
 * Ownership policy for a form-calculation target.
 *
 * <p>The first platform contract intentionally supports only {@link #ALWAYS}. Future policies,
 * such as preserving a user-edited value, belong here rather than in a second formula language.</p>
 */
public enum FormComputeWritePolicy {
    ALWAYS
}
