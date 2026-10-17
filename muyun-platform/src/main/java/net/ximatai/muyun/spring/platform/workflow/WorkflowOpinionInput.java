package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import java.util.function.Supplier;

/** The explicit language of this operation's human opinions; never derived from display preferences. */
public final class WorkflowOpinionInput {
    private static final ThreadLocal<String> LANGUAGE = new ThreadLocal<>();
    private WorkflowOpinionInput() { }
    public static String language() { return LANGUAGE.get(); }
    public static <T> T withLanguage(String language, Supplier<T> action) {
        String tag = new ContentWriteContext.SourceInput(language, null).languageTag();
        String previous = LANGUAGE.get();
        if (tag == null) LANGUAGE.remove(); else LANGUAGE.set(tag);
        try { return action.get(); }
        finally { if (previous == null) LANGUAGE.remove(); else LANGUAGE.set(previous); }
    }
}
