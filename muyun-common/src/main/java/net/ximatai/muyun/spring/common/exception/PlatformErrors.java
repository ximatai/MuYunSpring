package net.ximatai.muyun.spring.common.exception;

import java.util.List;
import java.util.Map;

public final class PlatformErrors {
    private PlatformErrors() {
    }

    public static PlatformException validation(String code, String message, ErrorTarget target) {
        return new PlatformException(code, 422, message, ErrorScope.empty(), List.of(target), Map.of());
    }

    public static PlatformException validation(String code, String message, List<ErrorTarget> targets) {
        return new PlatformException(code, 422, message, ErrorScope.empty(), targets, Map.of());
    }

    public static PlatformException conflict(String code, String message, Map<String, Object> details) {
        return conflict(code, message, ErrorScope.empty(), details);
    }

    public static PlatformException conflict(String code,
                                             String message,
                                             ErrorScope scope,
                                             Map<String, Object> details) {
        return new PlatformException(code, 409, message, scope, List.of(), details);
    }

    public static PlatformException conflict(String code,
                                             String message,
                                             Throwable cause,
                                             ErrorScope scope,
                                             Map<String, Object> details) {
        return new PlatformException(code, 409, message, cause, scope, List.of(), details);
    }

    public static PlatformException config(String code, String message, ErrorScope scope) {
        return new PlatformConfigurationException(code, message, scope);
    }

    public static PlatformException badRequest(String code, String message) {
        return new PlatformException(code, 400, message, ErrorScope.empty(), List.of(), Map.of());
    }

    public static PlatformException badRequest(String code, String message, List<ErrorTarget> targets) {
        return new PlatformException(code, 400, message, ErrorScope.empty(), targets, Map.of());
    }

    public static PlatformException notFound(String message, ErrorScope scope) {
        return new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404, message, scope, List.of(), Map.of());
    }
}
