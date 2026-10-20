package net.ximatai.muyun.spring.ability.code;

import java.lang.annotation.*;

/** Declares a persisted string field as a business code, independently of any UI. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BusinessCode {
    boolean primary() default false;
    CodeTrigger trigger() default CodeTrigger.SAVE;
}
