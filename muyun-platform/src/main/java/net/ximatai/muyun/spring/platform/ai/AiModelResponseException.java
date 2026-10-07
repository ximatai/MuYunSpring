package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A rejected response still consumed a model request; retain only content-free observations. */
public final class AiModelResponseException extends PlatformException {
    public AiModelResponseException(PlatformException failure, AiTokenUsage usage, Integer toolCallCount) {
        super(failure.code(), failure.httpStatus(), failure.getMessage(), failure.scope(), failure.targets(),
                observations(failure, usage, toolCallCount), failure.messageArgs());
    }
    private static Map<String, Object> observations(PlatformException failure, AiTokenUsage usage, Integer toolCallCount) {
        var details = new LinkedHashMap<>(failure.details());
        if (usage != null) details.put("modelUsage", usage);
        if (toolCallCount != null && toolCallCount >= 0) details.put("modelToolCallCount", toolCallCount);
        return details;
    }
}
