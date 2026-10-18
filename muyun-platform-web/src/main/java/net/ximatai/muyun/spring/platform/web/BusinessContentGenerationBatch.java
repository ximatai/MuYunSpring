package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationService;
import java.util.*;

/** A bounded, explicit selection. It carries no source text, route, actor or permission snapshot. */
public final class BusinessContentGenerationBatch {
    private BusinessContentGenerationBatch() { }
    static final int MAX_RECORDS = 200;
    static final int MAX_TEXTS = 200;
    static RuntimeException tooLarge() {
        return net.ximatai.muyun.spring.common.exception.PlatformErrors.badRequestText(
                net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.content.batch-limit",
                        "本次范围过大，请减少所选记录或取消包含明细（最多 200 条记录及 200 项文字）"));
    }

    public record Command(List<String> ids, String language, boolean retry, boolean includeChildren) {
        public Command {
            if (ids == null || ids.isEmpty() || ids.size() > 50
                    || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
                throw new IllegalArgumentException("Select between 1 and 50 business records");
            ids = List.copyOf(new LinkedHashSet<>(ids));
            if (language == null || !Set.of("zh-CN", "en", "de").contains(language))
                throw new IllegalArgumentException("Automatic reading language is not enabled");
        }
    }
    public record Item(String recordId, String ownerId, String relationCode, String childId, String field,
                       BusinessContentGenerationService.Status status) { }
    public record Result(String language, List<Item> items) {
        public Result { items = List.copyOf(items); }
    }
}
