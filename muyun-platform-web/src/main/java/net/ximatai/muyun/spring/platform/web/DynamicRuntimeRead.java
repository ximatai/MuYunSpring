package net.ximatai.muyun.spring.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Synchronous transport that consumes installed dynamic entity/page projections. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicRuntimeRead {
}
