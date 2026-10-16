package net.ximatai.muyun.spring.common.content;

import java.util.Map;

/** Write intent on one business record; not a reading-language preference or persisted business field. */
public interface ContentLanguageInputs {
    Map<String, ContentWriteContext.SourceInput> contentLanguageInputs();
    void contentLanguageInputs(Map<String, ContentWriteContext.SourceInput> inputs);
}
