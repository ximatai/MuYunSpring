package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationAction;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BusinessNotificationCommandDispatcher {
    private final Map<String, BusinessNotificationCommandHandler> handlers;

    public BusinessNotificationCommandDispatcher(List<BusinessNotificationCommandHandler> handlers) {
        Map<String, BusinessNotificationCommandHandler> registered = new LinkedHashMap<>();
        for (BusinessNotificationCommandHandler handler : handlers == null ? List.<BusinessNotificationCommandHandler>of() : handlers) {
            String command = BusinessNotificationAction.required(handler.command(), "business notification command");
            if (registered.putIfAbsent(command, handler) != null) {
                throw new IllegalStateException("Duplicate business notification command handler: " + command);
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public Object dispatch(String command, BusinessNotificationCommandRequest request) {
        CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        String normalized = BusinessNotificationAction.required(command, "business notification command");
        BusinessNotificationCommandHandler handler = handlers.get(normalized);
        if (handler == null) throw new IllegalArgumentException("Unknown business notification command: " + normalized);
        return handler.handle(request.invocation());
    }
}
