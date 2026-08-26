package net.ximatai.muyun.spring.platform.web.realtime;

import net.ximatai.muyun.spring.web.realtime.*;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailability;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailabilityService;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.user.UserSessionLifecycleEvent;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = RealtimeWebSocketIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/api"
)
class RealtimeWebSocketIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private DataChangeRealtimePublisher dataChangeRealtimePublisher;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private PlatformRecordActionAvailabilityService actionAvailabilityService;

    @Autowired
    private SimpUserRegistry userRegistry;

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
            "net.ximatai.muyun.database.spring.boot.MuYunDatabaseAutoConfiguration"
    })
@Import(net.ximatai.muyun.spring.iam.web.realtime.MuYunSpringRealtimeConfiguration.class)
    static class TestApplication {
        @Bean
        UserSessionService userSessionService() {
            return mock(UserSessionService.class);
        }

        @Bean
        PlatformRecordActionAvailabilityService actionAvailabilityService() {
            return mock(PlatformRecordActionAvailabilityService.class);
        }
    }

    @Test
    @SuppressWarnings("removal")
    void shouldDeliverDataChangeEnvelopeOnlyToCurrentUserQueue() throws Exception {
        when(userSessionService.currentUser("token-1"))
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User 1", "tenant-a")));
        when(userSessionService.currentUser("token-2"))
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-2", "User 2", "tenant-b")));
        when(userSessionService.currentSessionId("token-1")).thenReturn(Optional.of("session-1"));
        when(userSessionService.currentSessionId("token-2")).thenReturn(Optional.of("session-2"));
        WebSocketStompClient stompClient = stompClient();
        BlockingQueue<JsonNode> user1Messages = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> user2Messages = new LinkedBlockingQueue<>();

        StompSession user1Session = connect(stompClient, "token-1");
        StompSession user2Session = connect(stompClient, "token-2");
        try {
            user1Session.subscribe(userDataChangeDestination(), frameHandler(user1Messages));
            user2Session.subscribe(userDataChangeDestination(), frameHandler(user2Messages));
            awaitServerSubscriptions(userDataChangeDestination(), 2);

            try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                    CurrentUser.tenantUser("user-1", "User 1", "tenant-a"))) {
                dataChangeRealtimePublisher.publish(new CommittedChangeSet("change-set-1",
                        List.of(DataChange.recordUpdated("iam.employee", "employee-1"))));
            }

            JsonNode envelope = user1Messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(envelope).isNotNull();
            assertThat(envelope.path("type").asText()).isEqualTo(StompDataChangeRealtimePublisher.MESSAGE_TYPE);
            assertThat(envelope.path("payload").path("changeSetId").asText()).isEqualTo("change-set-1");
            assertThat(envelope.path("payload").path("changes").get(0).path("moduleAlias").asText())
                    .isEqualTo("iam.employee");
            assertThat(user2Messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            if (user1Session.isConnected()) {
                user1Session.disconnect();
            }
            if (user2Session.isConnected()) {
                user2Session.disconnect();
            }
            stompClient.stop();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void shouldDeliverDataChangeEnvelopeToEverySessionOfSameUser() throws Exception {
        CurrentUser admin = CurrentUser.systemUser("admin-1", "Admin");
        when(userSessionService.currentUser("token-admin-a")).thenReturn(Optional.of(admin));
        when(userSessionService.currentUser("token-admin-b")).thenReturn(Optional.of(admin));
        when(userSessionService.currentSessionId("token-admin-a")).thenReturn(Optional.of("admin-session-a"));
        when(userSessionService.currentSessionId("token-admin-b")).thenReturn(Optional.of("admin-session-b"));
        WebSocketStompClient stompClient = stompClient();
        BlockingQueue<JsonNode> adminAMessages = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> adminBMessages = new LinkedBlockingQueue<>();

        StompSession adminASession = connect(stompClient, "token-admin-a");
        StompSession adminBSession = connect(stompClient, "token-admin-b");
        try {
            adminASession.subscribe(userDataChangeDestination(), frameHandler(adminAMessages));
            adminBSession.subscribe(userDataChangeDestination(), frameHandler(adminBMessages));
            awaitServerSubscriptions(userDataChangeDestination(), 2);

            try (CurrentUserContext.Scope ignored = CurrentUserContext.use(admin)) {
                dataChangeRealtimePublisher.publish(new CommittedChangeSet("change-set-1",
                        List.of(DataChange.recordUpdated("iam.employee", "employee-1"))));
            }

            assertDataChangeEnvelope(adminAMessages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "change-set-1", "iam.employee", "employee-1");
            assertDataChangeEnvelope(adminBMessages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "change-set-1", "iam.employee", "employee-1");
        } finally {
            if (adminASession.isConnected()) {
                adminASession.disconnect();
            }
            if (adminBSession.isConnected()) {
                adminBSession.disconnect();
            }
            stompClient.stop();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void shouldPublishUserSessionLifecycleEventToConnectedAuthorizedUser() throws Exception {
        CurrentUser admin = CurrentUser.systemUser("platform.user.super_admin", "Admin");
        when(userSessionService.currentUser("token-admin")).thenReturn(Optional.of(admin));
        when(userSessionService.currentSessionId("token-admin")).thenReturn(Optional.of("admin-session"));
        when(userSessionService.currentUserSnapshot("token-admin")).thenReturn(Optional.of(admin));
        when(actionAvailabilityService.recordActions("iam.user", "user-1")).thenReturn(
                new PlatformRecordActionAvailability("user-1",
                        List.of(new PlatformRecordActionAvailability.Action("sessions", true, null))));
        WebSocketStompClient stompClient = stompClient();
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        StompSession adminSession = connect(stompClient, "token-admin");
        try {
            adminSession.subscribe(userBusinessEventDestination(), frameHandler(messages));
            awaitServerSubscriptions(userBusinessEventDestination(), 1);

            applicationEventPublisher.publishEvent(UserSessionLifecycleEvent.loggedIn("user-1", "session-1"));

            JsonNode envelope = messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(envelope).isNotNull();
            assertThat(envelope.path("type").asText()).isEqualTo(StompBusinessRealtimeNotifier.MESSAGE_TYPE);
            assertThat(envelope.path("payload").path("type").asText())
                    .isEqualTo("iam.user.session.collectionChanged");
            assertThat(envelope.path("payload").path("recordId").asText()).isEqualTo("user-1");
            assertThat(envelope.path("payload").path("reason").asText()).isEqualTo("LOGGED_IN");
        } finally {
            if (adminSession.isConnected()) {
                adminSession.disconnect();
            }
            stompClient.stop();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void shouldPublishPresenceLifecycleEventsThroughStompConnection() throws Exception {
        CurrentUser admin = CurrentUser.systemUser("platform.user.super_admin", "Admin");
        CurrentUser user = CurrentUser.tenantUser("user-1", "User 1", "tenant-a");
        when(userSessionService.currentUser("token-admin")).thenReturn(Optional.of(admin));
        when(userSessionService.currentSessionId("token-admin")).thenReturn(Optional.of("admin-session"));
        when(userSessionService.currentUserSnapshot("token-admin")).thenReturn(Optional.of(admin));
        when(userSessionService.currentUser("token-user")).thenReturn(Optional.of(user));
        when(userSessionService.currentSessionId("token-user")).thenReturn(Optional.of("user-session-1"));
        when(actionAvailabilityService.recordActions("iam.user", "user-1")).thenReturn(
                new PlatformRecordActionAvailability("user-1",
                        List.of(new PlatformRecordActionAvailability.Action("sessions", true, null))));
        WebSocketStompClient stompClient = stompClient();
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        StompSession adminSession = connect(stompClient, "token-admin");
        StompSession userSession = null;
        try {
            adminSession.subscribe(userBusinessEventDestination(), frameHandler(messages));
            awaitServerSubscriptions(userBusinessEventDestination(), 1);

            userSession = connect(stompClient, "token-user");
            assertUserSessionBusinessEvent(messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "user-1", "PRESENCE_CONNECTED");

            userSession.disconnect();
            userSession = null;

            assertUserSessionBusinessEvent(messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "user-1", "PRESENCE_DISCONNECTED");
        } finally {
            if (userSession != null && userSession.isConnected()) {
                userSession.disconnect();
            }
            if (adminSession.isConnected()) {
                adminSession.disconnect();
            }
            stompClient.stop();
        }
    }

    private StompSession connect(WebSocketStompClient stompClient, String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync(
                        "ws://localhost:" + port + "/api/ws/platform",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }

    private StompFrameHandler frameHandler(BlockingQueue<JsonNode> messages) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((JsonNode) payload);
            }
        };
    }

    private String userDataChangeDestination() {
        return "/user" + RealtimeDestinations.DATA_CHANGES.destination();
    }

    private String userBusinessEventDestination() {
        return "/user" + RealtimeDestinations.USER_BUSINESS_EVENTS.destination();
    }

    private void assertDataChangeEnvelope(JsonNode envelope, String changeSetId, String moduleAlias,
                                          String recordId) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.path("type").asText()).isEqualTo(StompDataChangeRealtimePublisher.MESSAGE_TYPE);
        assertThat(envelope.path("payload").path("changeSetId").asText()).isEqualTo(changeSetId);
        assertThat(envelope.path("payload").path("changes").get(0).path("moduleAlias").asText())
                .isEqualTo(moduleAlias);
        assertThat(envelope.path("payload").path("changes").get(0).path("recordId").asText())
                .isEqualTo(recordId);
    }

    private void assertUserSessionBusinessEvent(JsonNode envelope, String userId, String reason) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.path("type").asText()).isEqualTo(StompBusinessRealtimeNotifier.MESSAGE_TYPE);
        assertThat(envelope.path("payload").path("type").asText())
                .isEqualTo("iam.user.session.collectionChanged");
        assertThat(envelope.path("payload").path("recordId").asText()).isEqualTo(userId);
        assertThat(envelope.path("payload").path("reason").asText()).isEqualTo(reason);
    }

    @AfterEach
    void awaitDisconnectedWebSocketSessions() throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (userRegistry.getUserCount() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(userRegistry.getUserCount()).isZero();
    }

    private void awaitServerSubscriptions(String destination, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (userRegistry.findSubscriptions(subscription -> destination.equals(subscription.getDestination())).size() >= count) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(userRegistry.findSubscriptions(subscription -> destination.equals(subscription.getDestination())))
                .hasSizeGreaterThanOrEqualTo(count);
    }
}
