package net.ximatai.muyun.spring.common.platform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CustomActionEndpoint {
    String value();

    String title() default "";

    PlatformActionLevel level() default PlatformActionLevel.DEFAULT;

    /**
     * Keeps independent endpoints in the same action policy model as standard module operations.
     * Login-required endpoints still need their domain service to constrain the business subject.
     */
    ActionAccessMode accessMode() default ActionAccessMode.AUTH_REQUIRED;

    boolean actionAuth() default true;

    boolean dataAuth() default false;

    String recordIdPathVariable() default "id";
}
