package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.web.realtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlineUserDataChangeRealtimeFanOutPublisherTest {
    @Test void refreshesRecipientsAndAuthorizesEachSendWithoutIdentifiersOrFacts() {
        var registry = new RealtimeConnectionRegistry();
        var sessions = mock(UserSessionService.class);
        var messages = mock(RealtimeMessagePublisher.class);
        var source = CurrentUser.systemUser("source", "Source");
        var viewer = CurrentUser.tenantUser("viewer", "Viewer", "tenant-a");
        registry.register("one", new CurrentUserPrincipal(viewer, "live", "session"));
        registry.register("two", new CurrentUserPrincipal(viewer, "live", "session"));
        registry.register("foreign", new CurrentUserPrincipal(CurrentUser.tenantUser("foreign", "Foreign", "tenant-b"), "foreign", "foreign"));
        registry.register("stale", new CurrentUserPrincipal(viewer, "revoked", "revoked"));
        when(sessions.currentUserSnapshot("live")).thenReturn(Optional.of(viewer));
        when(sessions.currentUserSnapshot("foreign")).thenReturn(Optional.of(CurrentUser.tenantUser("foreign", "Foreign", "tenant-b")));
        when(sessions.currentUserSnapshot("revoked")).thenReturn(Optional.empty());
        var allowed = new AtomicBoolean(true);
        var publisher = new OnlineUserDataChangeRealtimeFanOutPublisher(registry, sessions, messages, module -> {
            assertThat(CurrentUserContext.currentUser().orElseThrow()).isEqualTo(viewer);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return allowed.get() && module.equals("sales.order");
        });
        var facts = new CommittedChangeSet("change", List.of(
                new DataChange("record-updated", "sales.order", "private-id", "lines", "private-parent", Map.of("secret", "secret")),
                DataChange.recordDeleted("sales.order", "deleted-id"), DataChange.recordUpdated("hidden.module", "hidden-id")));
        try (var user = CurrentUserContext.use(source); var tenant = TenantContext.use("tenant-a")) {
            publisher.publish(facts);
            assertThat(CurrentUserContext.currentUser().orElseThrow()).isEqualTo(source);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            allowed.set(false); publisher.publish(facts);
            when(sessions.currentUserSnapshot("live")).thenReturn(Optional.empty()); publisher.publish(facts);
        }
        verify(messages).sendToUser(eq("viewer"), eq(RealtimeDestinations.DATA_CHANGES), argThat(value -> {
            var payload = (CommittedChangeSet) ((RealtimeEnvelope<?>) value).payload();
            return payload.changeSetId().equals("change") && payload.changes().equals(List.of(DataChange.collectionChanged("sales.order")));
        }));
        verifyNoMoreInteractions(messages);
    }

    @Test void preservesIdentityFreePageConfigurationFactsAcrossBusinessTenants() {
        var registry = new RealtimeConnectionRegistry(); var sessions = mock(UserSessionService.class);
        var messages = mock(RealtimeMessagePublisher.class);
        var viewer = CurrentUser.tenantUser("viewer", "Viewer", "tenant-b");
        registry.register("one", new CurrentUserPrincipal(viewer, "live", "session"));
        when(sessions.currentUserSnapshot("live")).thenReturn(Optional.of(viewer));
        var publisher = new OnlineUserDataChangeRealtimeFanOutPublisher(registry, sessions, messages, module -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant-b"); return module.equals("sales.order");
        });
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("source", "Source"));
             var tenant = TenantContext.system("configuration publication")) {
            publisher.publish(new CommittedChangeSet("config", List.of(
                    new DataChange("module-page-configuration-changed", "sales.order", null, null, null, Map.of("secret", "ignored")),
                    DataChange.recordUpdated("sales.order", "tenant-a-record"))));
        }
        verify(messages).sendToUser(eq("viewer"), eq(RealtimeDestinations.DATA_CHANGES), argThat(value ->
                ((CommittedChangeSet) ((RealtimeEnvelope<?>) value).payload()).changes().equals(List.of(
                        new DataChange("module-page-configuration-changed", "sales.order", null, null, null, Map.of())))));
        verifyNoMoreInteractions(messages);
    }

    @Test void aFailingRecipientPolicyDoesNotBreakSourceOrLeakContext() {
        var registry = new RealtimeConnectionRegistry(); var sessions = mock(UserSessionService.class);
        var messages = mock(RealtimeMessagePublisher.class);
        var viewer = CurrentUser.tenantUser("viewer", "Viewer", "tenant-a");
        registry.register("one", new CurrentUserPrincipal(viewer, "live", "session"));
        when(sessions.currentUserSnapshot("live")).thenReturn(Optional.of(viewer));
        var publisher = new OnlineUserDataChangeRealtimeFanOutPublisher(registry, sessions, messages, module -> { throw new IllegalStateException("policy unavailable"); });
        var source = CurrentUser.tenantUser("source", "Source", "tenant-a");
        try (var user = CurrentUserContext.use(source); var tenant = TenantContext.use("tenant-a")) {
            publisher.publish(new CommittedChangeSet("change", List.of(DataChange.recordUpdated("sales.order", "id"))));
            assertThat(CurrentUserContext.currentUser().orElseThrow()).isEqualTo(source);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        }
        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/platform/modules/sales.order/data-changes", "/topic/platform/modules/sales.order/records/private-id/data-changes", "/queue/platform/data-changes", "/user/other/queue/platform/data-changes"})
    void rejectsSharedOrOtherUsersSubscriptionPaths(String destination) {
        var sessions = mock(UserSessionService.class); var user = CurrentUser.tenantUser("viewer", "Viewer", "tenant-b");
        when(sessions.currentUser("live")).thenReturn(Optional.of(user));
        var interceptor = new RealtimeAuthenticationChannelInterceptor(sessions);
        var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE); headers.setLeaveMutable(true);
        headers.setDestination(destination); headers.setUser(new CurrentUserPrincipal(user, "live", "session"));
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null))
                .hasMessageContaining("private user queue");
    }

    @Test void acceptsOwnUserQueueButRejectsDirectBrokerSend() {
        var sessions = mock(UserSessionService.class); var user = CurrentUser.tenantUser("viewer", "Viewer", "tenant-a");
        when(sessions.currentUser("live")).thenReturn(Optional.of(user));
        var interceptor = new RealtimeAuthenticationChannelInterceptor(sessions);
        for (var command : List.of(StompCommand.SUBSCRIBE, StompCommand.SEND)) {
            var headers = StompHeaderAccessor.create(command); headers.setLeaveMutable(true);
            headers.setDestination(command == StompCommand.SUBSCRIBE ? "/user/queue/platform/data-changes" : "/topic/platform/modules/sales.order/data-changes");
            headers.setUser(new CurrentUserPrincipal(user, "live", "session"));
            var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
            if (command == StompCommand.SUBSCRIBE) assertThat(interceptor.preSend(message, null)).isSameAs(message);
            else assertThatThrownBy(() -> interceptor.preSend(message, null)).hasMessageContaining("application destination");
        }
    }
}
