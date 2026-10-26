package net.ximatai.muyun.spring.common.i18n;

import java.util.Map;

/** Producer-declared message identity; consumers must retain undeclared original diagnostics. */
public interface TextSourceAware {
    TextReference textSource();
    Map<String, Object> messageArgs();
}
