package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;

/** Deployment capacities are declarations; null means unknown, never unlimited. */
record AiModelLimits(Integer contextWindowTokens, Integer maxOutputTokens, Integer defaultOutputTokens) {
    static final AiModelLimits UNKNOWN = new AiModelLimits(null, null, null);
    private static final int FALLBACK_OUTPUT_TOKENS = 8192;

    AiModelLimits {
        requirePositive(contextWindowTokens, "上下文容量");
        requirePositive(maxOutputTokens, "最大输出容量");
        requirePositive(defaultOutputTokens, "默认输出预算");
        if (contextWindowTokens != null && maxOutputTokens != null && maxOutputTokens > contextWindowTokens) {
            throw invalid("最大输出容量不能超过上下文容量");
        }
        int defaultBudget = defaultOutputTokens != null ? defaultOutputTokens
                : maxOutputTokens != null ? Math.min(FALLBACK_OUTPUT_TOKENS, maxOutputTokens) : FALLBACK_OUTPUT_TOKENS;
        if ((maxOutputTokens != null && defaultBudget > maxOutputTokens)
                || (contextWindowTokens != null && defaultBudget >= contextWindowTokens)) {
            throw invalid("默认输出预算必须在输出容量内，并为输入保留上下文空间");
        }
    }

    static AiModelLimits from(AiModelConfiguration configuration) {
        return new AiModelLimits(configuration.getContextWindowTokens(), configuration.getMaxOutputTokens(),
                configuration.getDefaultOutputTokens());
    }

    int outputBudget(Integer requested) {
        int budget = requested != null ? requested
                : defaultOutputTokens != null ? defaultOutputTokens
                : maxOutputTokens != null ? Math.min(FALLBACK_OUTPUT_TOKENS, maxOutputTokens) : FALLBACK_OUTPUT_TOKENS;
        if (budget < 1 || (maxOutputTokens != null && budget > maxOutputTokens)
                || (contextWindowTokens != null && budget >= contextWindowTokens)) {
            throw new PlatformException("AI_OUTPUT_BUDGET_EXCEEDED", 422,
                    "本次输出预算超过模型容量，或未为输入留出空间，请调整智能模型配置或调用预算");
        }
        return budget;
    }

    private static void requirePositive(Integer value, String label) {
        if (value != null && value < 1) throw invalid(label + "必须为正整数，未知时请留空");
    }

    private static PlatformException invalid(String message) {
        return new PlatformException("AI_MODEL_LIMITS_INVALID", 422, message);
    }
}
