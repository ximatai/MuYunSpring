package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.List;
import java.util.LinkedHashSet;

/** Reading intent only; never an authorization, original-language or interface-language input. */
public final class BusinessContentQueryWebContext {
    public static final String LANGUAGE_HEADER = "X-MuYun-Content-Language";
    public static final String LANGUAGES_HEADER = "X-MuYun-Content-Languages";
    private BusinessContentQueryWebContext() { }

    public static Optional<String> language() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            return Optional.empty();
        String value = attributes.getRequest().getHeader(LANGUAGE_HEADER);
        return Optional.ofNullable(new ContentWriteContext.SourceInput(value, null).languageTag());
    }

    /** Explicit bounded presentation batch, independent of interface language and generation policy. */
    public static List<String> languages() {
        var tags = new LinkedHashSet<String>();
        language().ifPresent(tags::add);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String values = attributes.getRequest().getHeader(LANGUAGES_HEADER);
            if (values != null) {
                if (values.length() > 2048) throw new IllegalArgumentException("Too many content presentation languages");
                for (String value : values.split(",", -1)) {
                    String tag = new ContentWriteContext.SourceInput(value, null).languageTag();
                    if (tag == null) throw new IllegalArgumentException("Content presentation language is required");
                    tags.add(tag);
                    if (tags.size() > 8) throw new IllegalArgumentException("Too many content presentation languages");
                }
            }
        }
        return List.copyOf(tags);
    }
}
