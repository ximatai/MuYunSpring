package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.iam.user.UserSessionLifecycleEvent;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.web.realtime.CurrentUserPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;
import java.util.Optional;

public class RealtimeAuthenticationChannelInterceptor implements ChannelInterceptor {
    private final UserSessionService userSessionService;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;

    public RealtimeAuthenticationChannelInterceptor(UserSessionService userSessionService) {
        this(userSessionService, new RealtimeConnectionRegistry(), null);
    }

    public RealtimeAuthenticationChannelInterceptor(UserSessionService userSessionService,
                                                    RealtimeConnectionRegistry connectionRegistry) {
        this(userSessionService, connectionRegistry, null);
    }

    public RealtimeAuthenticationChannelInterceptor(UserSessionService userSessionService,
                                                    RealtimeConnectionRegistry connectionRegistry,
                                                    ApplicationEventPublisher applicationEventPublisher) {
        this.userSessionService = userSessionService;
        this.connectionRegistry = connectionRegistry;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(command)) {
            String token = bearerToken(accessor);
            CurrentUser currentUser = authenticate(token)
                    .orElseThrow(() -> new IllegalArgumentException("realtime authentication required"));
            if (currentUser.passwordChangeRequired()) {
                throw new IllegalArgumentException("password change required");
            }
            String loginSessionId = userSessionService.currentSessionId(token)
                    .orElseThrow(() -> new IllegalArgumentException("realtime authentication required"));
            CurrentUserPrincipal principal = new CurrentUserPrincipal(currentUser, token, loginSessionId);
            accessor.setUser(principal);
            if (connectionRegistry.register(accessor.getSessionId(), principal)) {
                publishPresenceConnected(principal);
            }
            return message;
        }
        if (StompCommand.DISCONNECT.equals(command)) {
            CurrentUserPrincipal principal = connectionRegistry.unregister(accessor.getSessionId());
            publishPresenceDisconnected(principal);
            return message;
        }
        if (StompCommand.SUBSCRIBE.equals(command) || StompCommand.SEND.equals(command)) {
            CurrentUserPrincipal principal = currentUserPrincipal(accessor.getUser())
                    .orElseThrow(() -> new IllegalArgumentException("realtime authentication required"));
            CurrentUser currentUser = authenticate(principal.token())
                    .orElseThrow(() -> new IllegalArgumentException("realtime authentication required"));
            if (currentUser.passwordChangeRequired()) {
                throw new IllegalArgumentException("password change required");
            }
            if (StompCommand.SUBSCRIBE.equals(command)) {
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/user/queue/")) {
                    throw new IllegalArgumentException("realtime subscription requires a private user queue");
                }
            }
            if (StompCommand.SEND.equals(command)) {
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new IllegalArgumentException("realtime send requires an application destination");
                }
            }
            CurrentUserPrincipal refreshedPrincipal = new CurrentUserPrincipal(currentUser, principal.token(),
                    principal.loginSessionId());
            accessor.setUser(refreshedPrincipal);
            connectionRegistry.register(accessor.getSessionId(), refreshedPrincipal);
            connectionRegistry.touch(accessor.getSessionId());
        }
        return message;
    }

    private void publishPresenceConnected(CurrentUserPrincipal principal) {
        if (applicationEventPublisher == null || principal == null || principal.loginSessionId() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(UserSessionLifecycleEvent.presenceConnected(
                principal.currentUser().userId(), principal.loginSessionId()));
    }

    private void publishPresenceDisconnected(CurrentUserPrincipal principal) {
        if (applicationEventPublisher == null || principal == null || principal.loginSessionId() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(UserSessionLifecycleEvent.presenceDisconnected(
                principal.currentUser().userId(), principal.loginSessionId()));
    }

    private Optional<CurrentUser> authenticate(String token) {
        return userSessionService.currentUser(token);
    }

    private String bearerToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return header.substring(prefix.length()).trim();
    }

    public static CurrentUser currentUser(Principal principal) {
        return currentUserPrincipal(principal)
                .map(CurrentUserPrincipal::currentUser)
                .orElse(null);
    }

    private static Optional<CurrentUserPrincipal> currentUserPrincipal(Principal principal) {
        if (principal instanceof CurrentUserPrincipal currentUserPrincipal) {
            return Optional.of(currentUserPrincipal);
        }
        return Optional.empty();
    }
}
