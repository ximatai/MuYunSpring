package net.ximatai.muyun.spring.platform.web;

/**
 * Explicit, temporary opt-in for a static controller that has not migrated its read projection
 * requests to the compiled module execution plan yet.
 *
 * <p>The marker is deliberately controller-facing: a missing plan must never make an arbitrary
 * static module recompile its UI declaration during a request. New controllers must use the
 * standard runtime instead.</p>
 */
public interface LegacyStaticReadProjectionCompatibility {
}
