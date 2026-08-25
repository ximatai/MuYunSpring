package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a scalar field whose value semantics are selected by a sibling enum field.
 *
 * <p>This is deliberately not an expression hook: every branch is either a fixed value,
 * another field, or one ordinary platform reference. Static and dynamic declarations compile
 * to the same plan.</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DiscriminatedValue {
    String discriminator();

    Class<? extends CodeTitleEnum> enumType();

    DiscriminatedValueCase[] cases();
}
