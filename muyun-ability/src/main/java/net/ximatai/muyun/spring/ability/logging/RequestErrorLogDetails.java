package net.ximatai.muyun.spring.ability.logging;

/** Typed internal and external details for a failed HTTP request. */
public record RequestErrorLogDetails(
        String method,
        String path,
        String endpointId,
        long durationMillis,
        int httpStatus,
        String errorCode,
        LogText responseSummary,
        String exceptionType,
        LogText exceptionMessage,
        LogText stackTrace,
        String failureStage,
        boolean responseCompleted
) implements BusinessLogDetails {
    public RequestErrorLogDetails {
        method = BusinessLogContext.required(method, "method", 16);
        path = BusinessLogContext.required(path, "path", 1_024);
        endpointId = BusinessLogContext.optional(endpointId, "endpointId", 256);
        if (durationMillis < 0 || httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("durationMillis or httpStatus is invalid");
        }
        errorCode = BusinessLogContext.optional(errorCode, "errorCode", 128);
        exceptionType = BusinessLogContext.optional(exceptionType, "exceptionType", 512);
        failureStage = BusinessLogContext.optional(failureStage, "failureStage", 128);
    }
}
