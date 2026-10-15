package net.ximatai.muyun.spring.common.exception;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PlatformException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final ErrorScope scope;
    private final List<ErrorTarget> targets;
    private final Map<String, Object> details;
    private final Map<String, Object> messageArgs;
    private final TextReference textSource;

    public PlatformException(String message) {
        this(PlatformErrorCodes.VALIDATION_FAILED, 400, message);
    }

    public PlatformException(String message, Throwable cause) {
        this(PlatformErrorCodes.VALIDATION_FAILED, 400, message, cause);
    }

    public PlatformException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, ErrorScope.empty(), List.of(), Map.of());
    }

    public PlatformException(String code, int httpStatus, String message, Throwable cause) {
        this(code, httpStatus, message, cause, ErrorScope.empty(), List.of(), Map.of());
    }

    public PlatformException(String code,
                             int httpStatus,
                             String message,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details) {
        this(code, httpStatus, message, scope, targets, details, Map.of());
    }

    public PlatformException(String code,
                             int httpStatus,
                             String message,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details,
                             Map<String, Object> messageArgs) {
        this(code, httpStatus, message, scope, targets, details, messageArgs, null);
    }

    protected PlatformException(String code,
                             int httpStatus,
                             String message,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details,
                             Map<String, Object> messageArgs,
                             TextReference textSource) {
        // Keep the existing no-cause constructor contract, including Throwable.initCause.
        super(message);
        this.code = normalizeCode(code);
        this.httpStatus = httpStatus;
        this.scope = scope == null ? ErrorScope.empty() : scope;
        this.targets = copyTargets(targets);
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
        this.textSource = textSource;
    }

    public PlatformException(String code,
                             int httpStatus,
                             String message,
                             Throwable cause,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details) {
        this(code, httpStatus, message, cause, scope, targets, details, Map.of());
    }

    public PlatformException(String code,
                             int httpStatus,
                             String message,
                             Throwable cause,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details,
                             Map<String, Object> messageArgs) {
        this(code, httpStatus, message, cause, scope, targets, details, messageArgs, null);
    }

    public PlatformException(String code,
                             int httpStatus,
                             String message,
                             Throwable cause,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details,
                             Map<String, Object> messageArgs,
                             TextReference textSource) {
        super(message, cause);
        this.code = normalizeCode(code);
        this.httpStatus = httpStatus;
        this.scope = scope == null ? ErrorScope.empty() : scope;
        this.targets = copyTargets(targets);
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
        this.textSource = textSource;
    }

    public TextReference textSource() {
        return textSource;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ErrorScope scope() {
        return scope;
    }

    public List<ErrorTarget> targets() {
        return targets;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Map<String, Object> messageArgs() {
        return messageArgs;
    }

    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? PlatformErrorCodes.VALIDATION_FAILED : code;
    }

    private static List<ErrorTarget> copyTargets(List<ErrorTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
