package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.*;

/**
 * Declares that all entry constraints are represented by the compiled module, menu and normal Service policies.
 * Overrides do not inherit this promise. Request-only constraints require a separate trusted deferred scope adapter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CompiledRecordScope {
    Kind value() default Kind.READ;
    enum Kind { READ, SAVE, EXECUTION }
}
