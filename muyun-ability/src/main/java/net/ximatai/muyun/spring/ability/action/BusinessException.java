package net.ximatai.muyun.spring.ability.action;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.List;
import java.util.Map;

public class BusinessException extends PlatformException {
    private final ActionMessageType messageType;

    public BusinessException(String code, String message) {
        this(code, message, ActionMessageType.WARNING);
    }

    public BusinessException(String code, String message, ActionMessageType messageType) {
        this(code, 422, message, messageType);
    }

    public BusinessException(String code, int httpStatus, String message, ActionMessageType messageType) {
        this(code, httpStatus, message, messageType, ErrorScope.empty(), List.of(), Map.of());
    }

    public BusinessException(String code,
                             int httpStatus,
                             String message,
                             ActionMessageType messageType,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details) {
        this(code, httpStatus, message, messageType, scope, targets, details, Map.of());
    }

    public BusinessException(String code,
                             int httpStatus,
                             String message,
                             ActionMessageType messageType,
                             ErrorScope scope,
                             List<ErrorTarget> targets,
                             Map<String, Object> details,
                             Map<String, Object> messageArgs) {
        this(code, httpStatus, message, messageType, scope, targets, details, messageArgs, null);
    }

    public BusinessException(String code, TextReference source,
                             ActionMessageType messageType, Map<String, Object> args) {
        this(code, 422, TextResources.format(source.originalText(), args),
                messageType, ErrorScope.empty(), List.of(), Map.of(), args, source);
    }

    public BusinessException(String code, int httpStatus, String message, ActionMessageType messageType,
                             ErrorScope scope, List<ErrorTarget> targets, Map<String, Object> details,
                             Map<String, Object> messageArgs, TextReference textSource) {
        super(textSource, code, httpStatus, message, scope, targets, details, messageArgs);
        this.messageType = messageType == null ? ActionMessageType.WARNING : messageType;
    }

    public ActionMessage actionMessage() {
        return new ActionMessage(code(), getMessage(), messageType, messageArgs(), textSource());
    }

    public ActionMessageType messageType() {
        return messageType;
    }
}
