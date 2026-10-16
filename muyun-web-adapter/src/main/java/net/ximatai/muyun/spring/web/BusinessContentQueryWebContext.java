package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** Reading intent only; never an authorization, original-language or interface-language input. */
public final class BusinessContentQueryWebContext {
    public static final String LANGUAGE_HEADER = "X-MuYun-Content-Language";
    private BusinessContentQueryWebContext() { }

    public static Optional<String> language() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            return Optional.empty();
        String value = attributes.getRequest().getHeader(LANGUAGE_HEADER);
        return Optional.ofNullable(new ContentWriteContext.SourceInput(value, null).languageTag());
    }
}
