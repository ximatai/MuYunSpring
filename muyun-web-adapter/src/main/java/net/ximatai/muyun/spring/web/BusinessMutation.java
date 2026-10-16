package net.ximatai.muyun.spring.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BusinessMutation {
    /**
     * Module actions require their resolved action context by default. Endpoints whose domain
     * service authorizes an indirect target (such as an assigned task) can collect mutation facts
     * without a module action context. This flag never grants permission to execute the endpoint.
     */
    boolean actionContextRequired() default true;
}
