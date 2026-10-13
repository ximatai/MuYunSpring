package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;

import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;

public record PlatformWebError(String traceId,
                               String code,
                               int status,
                               String message,
                               ActionMessage actionMessage,
                               ErrorScope scope,
                               List<ErrorTarget> targets,
                               Map<String, Object> details,
                               Map<String, Object> messageArgs, TextReference textSource) {
    public PlatformWebError(String traceId, String code, int status, String message, ActionMessage actionMessage,
                            ErrorScope scope, List<ErrorTarget> targets, Map<String, Object> details,
                            Map<String, Object> messageArgs) {
        this(traceId, code, status, message, actionMessage, scope, targets, details, messageArgs, null);
    }

    public PlatformWebError withTextSource(TextReference source) {
        return new PlatformWebError(traceId, code, status, message, actionMessage, scope, targets, details, messageArgs, source);
    }

    public PlatformWebError withDisplayText(String value, ActionMessage action) {
        return new PlatformWebError(traceId, code, status, value, action, scope, targets, details, messageArgs, textSource);
    }

    public static PlatformWebError of(PlatformException exception) {
        return new PlatformWebError(
                responseTraceId(),
                exception.code(),
                exception.httpStatus(),
                exception.getMessage(),
                null,
                emptyScopeAsNull(exception.scope()),
                exception.targets().isEmpty() ? List.of() : exception.targets(),
                exception.details().isEmpty() ? Map.of() : exception.details(),
                exception.messageArgs().isEmpty() ? Map.of() : exception.messageArgs());
    }

    public static PlatformWebError of(String code, int status, String message) {
        return new PlatformWebError(responseTraceId(), code, status, message, null, null, List.of(),
                Map.of(), Map.of());
    }

    public static PlatformWebError of(String code, int status, String message, ActionMessage actionMessage) {
        return new PlatformWebError(responseTraceId(), code, status, message, actionMessage, null, List.of(),
                Map.of(), Map.of());
    }

    public static PlatformWebError of(String code, int status, String message, Map<String, Object> details) {
        return new PlatformWebError(responseTraceId(), code, status, message, null, null, List.of(),
                details == null ? Map.of() : Map.copyOf(details), Map.of());
    }

    public PlatformWebError withActionMessage(ActionMessage actionMessage) {
        return new PlatformWebError(traceId, code, status, message, actionMessage, scope, targets, details, messageArgs, textSource);
    }

    private static ErrorScope emptyScopeAsNull(ErrorScope scope) {
        return scope == null || scope.isEmpty() ? null : scope;
    }

    private static String responseTraceId() {
        return RequestTraceContext.currentTraceId().orElseGet(Ids::newId);
    }
}
