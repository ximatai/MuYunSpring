package net.ximatai.muyun.spring.ability.action;

import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;

import java.util.List;
import java.util.Map;

public final class BusinessExceptions {
    private BusinessExceptions() {
    }

    public static BusinessException warning(String code, String message) {
        return new BusinessException(code, message, ActionMessageType.WARNING);
    }

    public static BusinessException warning(String code, String message, Map<String, Object> messageArgs) {
        return new BusinessException(code, 422, message, ActionMessageType.WARNING,
                ErrorScope.empty(), List.of(), Map.of(), messageArgs);
    }

    public static BusinessException error(String code, String message) {
        return new BusinessException(code, message, ActionMessageType.ERROR);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(code, 409, message, ActionMessageType.WARNING);
    }

    public static BusinessException warning(String code,
                                            String message,
                                            ErrorScope scope,
                                            List<ErrorTarget> targets,
                                            Map<String, Object> details) {
        return new BusinessException(code, 422, message, ActionMessageType.WARNING, scope, targets, details);
    }

    public static BusinessException warning(String code,
                                            String message,
                                            ErrorScope scope,
                                            List<ErrorTarget> targets,
                                            Map<String, Object> details,
                                            Map<String, Object> messageArgs) {
        return new BusinessException(code, 422, message, ActionMessageType.WARNING,
                scope, targets, details, messageArgs);
    }
    public static BusinessException warningText(String code, TextReference source) {
        return warningText(code, source, Map.of());
    }

    public static BusinessException warningText(String code, TextReference source, Map<String, Object> args) {
        return warningText(code, source, ErrorScope.empty(), List.of(), Map.of(), args);
    }

    public static BusinessException warningText(String code, TextReference source, ErrorScope scope,
            List<ErrorTarget> targets, Map<String, Object> details) {
        return warningText(code, source, scope, targets, details, Map.of());
    }

    public static BusinessException warningText(String code, TextReference source, ErrorScope scope,
            List<ErrorTarget> targets, Map<String, Object> details, Map<String, Object> args) {
        return new BusinessException(code, 422, TextResources.format(source.originalText(), args), ActionMessageType.WARNING,
                scope, targets, details, args, source);
    }

    public static BusinessException errorText(String code, TextReference source) {
        return new BusinessException(code, source, ActionMessageType.ERROR, Map.of());
    }

    public static BusinessException conflictText(String code, TextReference source) {
        return new BusinessException(code, 409, source.originalText(), ActionMessageType.WARNING,
                ErrorScope.empty(), List.of(), Map.of(), Map.of(), source);
    }

}
