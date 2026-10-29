package net.ximatai.muyun.spring.common.model.constraint;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConditionalIndexes {
    ConditionalIndex[] value();
}
