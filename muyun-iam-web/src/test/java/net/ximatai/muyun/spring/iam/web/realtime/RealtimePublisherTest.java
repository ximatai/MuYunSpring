package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailability;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailabilityService;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.iam.user.UserSecurityEvent;
import net.ximatai.muyun.spring.iam.user.UserSessionLifecycleEvent;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.web.realtime.BusinessRealtimeEvent;
import net.ximatai.muyun.spring.web.realtime.BusinessRealtimeFanOutPublisher;
import net.ximatai.muyun.spring.web.realtime.BusinessRealtimeNotifier;
import net.ximatai.muyun.spring.web.realtime.CurrentUserPrincipal;
import net.ximatai.muyun.spring.web.realtime.RealtimeDestinations;
import net.ximatai.muyun.spring.web.realtime.RealtimeEnvelope;
import net.ximatai.muyun.spring.web.realtime.RealtimeMessagePublisher;
import net.ximatai.muyun.spring.web.realtime.RealtimeQueue;
import net.ximatai.muyun.spring.web.realtime.RealtimeTopic;
import net.ximatai.muyun.spring.web.realtime.SecurityNotification;
import net.ximatai.muyun.spring.web.realtime.SecurityRealtimeNotifier;
import net.ximatai.muyun.spring.web.realtime.StompBusinessRealtimeNotifier;
import net.ximatai.muyun.spring.web.realtime.StompDataChangeRealtimePublisher;
import net.ximatai.muyun.spring.web.realtime.StompSecurityRealtimeNotifier;
import net.ximatai.muyun.spring.platform.web.realtime.BusinessRealtimeRecipientPolicyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimePublisherTest {
    @Test
    void shouldSendDataChangeEnvelopeToCurrentUserQueue() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompDataChangeRealtimePublisher publisher = new StompDataChangeRealtimePublisher(messagePublisher);
        CommittedChangeSet changeSet = new CommittedChangeSet("change-set-1",
                List.of(DataChange.recordUpdated("iam.employee", "employee-1")));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            publisher.publish(changeSet);
        }

        assertThat(messagePublisher.userId).isEqualTo("user-1");
        assertThat(messagePublisher.queue).isEqualTo(RealtimeDestinations.DATA_CHANGES);
        assertThat(messagePublisher.payload).isInstanceOf(RealtimeEnvelope.class);
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) messagePublisher.payload;
        assertThat(envelope.type()).isEqualTo(StompDataChangeRealtimePublisher.MESSAGE_TYPE);
        assertThat(envelope.payload()).isEqualTo(changeSet);
    }

    @Test
    void shouldBroadcastOnlyDataChangeSummariesToModuleAndRecordTopics() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompDataChangeRealtimePublisher publisher = new StompDataChangeRealtimePublisher(messagePublisher);
        CurrentUser source = CurrentUser.tenantUser("source-1", "Source", "tenant-a");
        CommittedChangeSet changeSet = new CommittedChangeSet("change-set-1", List.of(
                new DataChange("record-updated", "iam.employee", "employee-1", null, null,
                        Map.of("name", "敏感姓名")),
                DataChange.recordUpdated("iam.employee", "employee-1"),
                DataChange.recordDeleted("iam.employee", "employee-2"),
                DataChange.collectionChanged("iam.user")));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(source)) {
            publisher.publish(changeSet);
        }

        assertThat(messagePublisher.userMessages)
                .extracting(UserMessage::userId)
                .containsExactly("source-1");
        assertThat(((RealtimeEnvelope<?>) messagePublisher.userMessages.get(0).payload()).payload())
                .isEqualTo(changeSet);
        assertThat(messagePublisher.broadcasts)
                .extracting(BroadcastMessage::topic)
                .containsExactly(
                        RealtimeDestinations.moduleDataChanges("iam.employee"),
                        RealtimeDestinations.moduleDataChanges("iam.user"),
                        RealtimeDestinations.recordDataChanges("iam.employee", "employee-1"),
                        RealtimeDestinations.recordDataChanges("iam.employee", "employee-2"));
        RealtimeEnvelope<?> employeeEnvelope = (RealtimeEnvelope<?>) messagePublisher.broadcasts.get(0).payload();
        assertThat(employeeEnvelope.payload()).isInstanceOf(CommittedChangeSet.class);
        CommittedChangeSet employeeSummary = (CommittedChangeSet) employeeEnvelope.payload();
        assertThat(employeeSummary.changeSetId()).isEqualTo("change-set-1");
        assertThat(employeeSummary.changes()).containsExactly(
                DataChange.recordUpdated("iam.employee", "employee-1"),
                DataChange.recordUpdated("iam.employee", "employee-1"),
                DataChange.recordDeleted("iam.employee", "employee-2"));
        assertThat(employeeSummary.changes())
                .allSatisfy(change -> assertThat(change.facts()).isEmpty());

        RealtimeEnvelope<?> collectionEnvelope = (RealtimeEnvelope<?>) messagePublisher.broadcasts.get(1).payload();
        CommittedChangeSet collectionSummary = (CommittedChangeSet) collectionEnvelope.payload();
        assertThat(collectionSummary.changes()).containsExactly(DataChange.collectionChanged("iam.user"));
    }

    @Test
    void shouldRouteResourceChangesToResourceChildAndOwningParentTopics() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompDataChangeRealtimePublisher publisher = new StompDataChangeRealtimePublisher(messagePublisher);
        DataChange resourceChange = DataChange.resourceRecordUpdated(
                "platform.field_ui_control", "properties", "control-1", "property-1");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("source-1", "Source", "tenant-a"))) {
            publisher.publish(new CommittedChangeSet("change-set-1", List.of(resourceChange)));
        }

        assertThat(messagePublisher.broadcasts)
                .extracting(BroadcastMessage::topic)
                .containsExactly(
                        RealtimeDestinations.moduleDataChanges("platform.field_ui_control"),
                        RealtimeDestinations.recordDataChanges("platform.field_ui_control", "control-1"),
                        RealtimeDestinations.resourceDataChanges("platform.field_ui_control", "properties"),
                        RealtimeDestinations.resourceRecordDataChanges(
                                "platform.field_ui_control", "properties", "property-1"));
        assertThat(messagePublisher.broadcasts)
                .extracting(BroadcastMessage::topic)
                .doesNotContain(RealtimeDestinations.recordDataChanges(
                        "platform.field_ui_control", "property-1"));
    }

    @Test
    void shouldSkipDataChangeWithoutCurrentUser() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompDataChangeRealtimePublisher publisher = new StompDataChangeRealtimePublisher(messagePublisher);

        publisher.publish(new CommittedChangeSet("change-set-1",
                List.of(DataChange.recordUpdated("iam.employee", "employee-1"))));

        assertThat(messagePublisher.payload).isNull();
    }

    @Test
    void shouldSkipEmptyChangeSet() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompDataChangeRealtimePublisher publisher = new StompDataChangeRealtimePublisher(messagePublisher);

        publisher.publish(CommittedChangeSet.empty("change-set-1"));

        assertThat(messagePublisher.payload).isNull();
    }

    @Test
    void shouldBuildStandardRealtimeDestinations() {
        assertThat(RealtimeDestinations.USER_IM_MESSAGES.destination()).isEqualTo("/queue/platform/im/messages");
        assertThat(RealtimeDestinations.IM_MESSAGES_SEND.destination()).isEqualTo("/app/platform/im/messages/send");
        assertThat(RealtimeDestinations.tenantPublicDataChanges("tenant-a").destination())
                .isEqualTo("/topic/platform/tenants/tenant-a/public/data-changes");
        assertThat(RealtimeDestinations.tenantPublicNotifications("tenant-a").destination())
                .isEqualTo("/topic/platform/tenants/tenant-a/public/notifications");
        assertThat(RealtimeDestinations.organizationPublicDataChanges("org-1").destination())
                .isEqualTo("/topic/platform/organizations/org-1/public/data-changes");
        assertThat(RealtimeDestinations.organizationPublicNotifications("org-1").destination())
                .isEqualTo("/topic/platform/organizations/org-1/public/notifications");
        assertThat(RealtimeDestinations.moduleDataChanges("iam.employee").destination())
                .isEqualTo("/topic/platform/modules/iam.employee/data-changes");
        assertThat(RealtimeDestinations.recordDataChanges("iam.employee", "employee-1").destination())
                .isEqualTo("/topic/platform/modules/iam.employee/records/employee-1/data-changes");
        assertThat(RealtimeDestinations.resourceDataChanges("iam.employee", "children").destination())
                .isEqualTo("/topic/platform/modules/iam.employee/resources/children/data-changes");
        assertThat(RealtimeDestinations.resourceRecordDataChanges("iam.employee", "children", "employee-1")
                .destination()).isEqualTo(
                        "/topic/platform/modules/iam.employee/resources/children/records/employee-1/data-changes");
        assertThat(RealtimeDestinations.contextDataChanges("workflow", "task-1").destination())
                .isEqualTo("/topic/platform/contexts/workflow/task-1/data-changes");
        assertThat(RealtimeDestinations.imConversationMessages("conversation-1").destination())
                .isEqualTo("/topic/platform/im/conversations/conversation-1/messages");
    }

    @Test
    void shouldEncodeRealtimeDestinationPathSegments() {
        assertThat(RealtimeDestinations.recordDataChanges("order/form", "record 1").destination())
                .isEqualTo("/topic/platform/modules/order%2Fform/records/record%201/data-changes");

        assertThatThrownBy(() -> RealtimeDestinations.moduleDataChanges(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("realtime destination path segment must not be blank");
    }

    @Test
    void shouldSendPasswordSecurityNotificationsToUserQueue() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompSecurityRealtimeNotifier notifier = new StompSecurityRealtimeNotifier(messagePublisher);

        try (RequestTraceContext.Scope ignored = RequestTraceContext.use("trace-1")) {
            notifier.notifyPasswordReset("user-1");
        }

        assertThat(messagePublisher.userId).isEqualTo("user-1");
        assertThat(messagePublisher.queue).isEqualTo(RealtimeDestinations.USER_NOTIFICATIONS);
        assertThat(messagePublisher.payload).isInstanceOf(RealtimeEnvelope.class);
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) messagePublisher.payload;
        assertThat(envelope.type()).isEqualTo(StompSecurityRealtimeNotifier.MESSAGE_TYPE);
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.payload()).isEqualTo(SecurityNotification.passwordReset());
    }

    @Test
    void shouldSendBusinessEventsToUserQueue() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompBusinessRealtimeNotifier notifier = new StompBusinessRealtimeNotifier(messagePublisher);
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN");

        try (RequestTraceContext.Scope ignored = RequestTraceContext.use("trace-1")) {
            notifier.notifyUser("admin-1", event);
        }

        assertThat(messagePublisher.userId).isEqualTo("admin-1");
        assertThat(messagePublisher.queue).isEqualTo(RealtimeDestinations.USER_BUSINESS_EVENTS);
        assertThat(messagePublisher.payload).isInstanceOf(RealtimeEnvelope.class);
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) messagePublisher.payload;
        assertThat(envelope.type()).isEqualTo(StompBusinessRealtimeNotifier.MESSAGE_TYPE);
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.payload()).isEqualTo(event);
        assertThat(messagePublisher.broadcasts).isEmpty();
    }

    @Test
    void shouldSkipBusinessEventsWithoutRecipientUser() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompBusinessRealtimeNotifier notifier = new StompBusinessRealtimeNotifier(messagePublisher);
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN");

        notifier.notifyUser(" ", event);
        notifier.notifyUser("admin-1", null);

        assertThat(messagePublisher.userMessages).isEmpty();
        assertThat(messagePublisher.broadcasts).isEmpty();
    }

    @Test
    void shouldBuildUserSessionBusinessEventAsDirtyMarkerOnly() {
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_OUT");

        assertThat(event.type()).isEqualTo("iam.user.session.collectionChanged");
        assertThat(event.moduleAlias()).isEqualTo("iam.user");
        assertThat(event.recordId()).isEqualTo("user-1");
        assertThat(event.reason()).isEqualTo("LOGGED_OUT");
        assertThat(event.sensitivity()).isEqualTo("DIRTY_MARKER");
    }

    @Test
    void shouldFanOutBusinessEventsOnlyToAllowedOnlineUsers() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        UserSessionService userSessionService = mock(UserSessionService.class);
        RecordingBusinessRealtimeNotifier notifier = new RecordingBusinessRealtimeNotifier();
        OnlineUserBusinessRealtimeFanOutPublisher publisher =
                new OnlineUserBusinessRealtimeFanOutPublisher(registry, userSessionService, notifier);
        CurrentUser admin = CurrentUser.systemUser("admin-1", "Admin");
        CurrentUser viewer = CurrentUser.tenantUser("viewer-1", "Viewer", "demo");
        CurrentUser target = CurrentUser.tenantUser("user-1", "User", "demo");
        registry.register("ws-admin", new CurrentUserPrincipal(admin, "token-admin-1"));
        registry.register("ws-viewer", new CurrentUserPrincipal(viewer, "token-viewer-1"));
        registry.register("ws-target", new CurrentUserPrincipal(target, "token-user-1"));
        when(userSessionService.currentUserSnapshot("token-admin-1")).thenReturn(Optional.of(admin));
        when(userSessionService.currentUserSnapshot("token-viewer-1")).thenReturn(Optional.of(viewer));
        when(userSessionService.currentUserSnapshot("token-user-1")).thenReturn(Optional.of(target));
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN");

        publisher.publish(event, currentUser -> "admin-1".equals(currentUser.userId()));

        assertThat(notifier.userIds).containsExactly("admin-1");
        assertThat(notifier.events).containsExactly(event);
    }

    @Test
    void shouldResolveConnectedRecipientsOutsideSourceTenantScope() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        UserSessionService userSessionService = mock(UserSessionService.class);
        RecordingBusinessRealtimeNotifier notifier = new RecordingBusinessRealtimeNotifier();
        OnlineUserBusinessRealtimeFanOutPublisher publisher =
                new OnlineUserBusinessRealtimeFanOutPublisher(registry, userSessionService, notifier);
        CurrentUser admin = CurrentUser.systemUser("platform.user.super_admin", "Admin");
        registry.register("ws-admin", new CurrentUserPrincipal(admin, "token-admin-1"));
        when(userSessionService.currentUserSnapshot("token-admin-1")).thenAnswer(invocation -> {
            assertThat(TenantContext.tenantFilterBypassed()).isTrue();
            return Optional.of(admin);
        });
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("demo-user-1", "LOGGED_IN");

        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            publisher.publish(event, currentUser -> true);
        }

        assertThat(notifier.userIds).containsExactly("platform.user.super_admin");
        assertThat(notifier.events).containsExactly(event);
    }

    @Test
    void shouldSkipBusinessEventFanOutWhenRecipientSessionIsNoLongerValid() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        UserSessionService userSessionService = mock(UserSessionService.class);
        RecordingBusinessRealtimeNotifier notifier = new RecordingBusinessRealtimeNotifier();
        OnlineUserBusinessRealtimeFanOutPublisher publisher =
                new OnlineUserBusinessRealtimeFanOutPublisher(registry, userSessionService, notifier);
        CurrentUser admin = CurrentUser.systemUser("admin-1", "Admin");
        registry.register("ws-admin", new CurrentUserPrincipal(admin, "token-admin-1"));
        when(userSessionService.currentUserSnapshot("token-admin-1")).thenReturn(Optional.empty());

        publisher.publish(BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN"),
                currentUser -> true);

        assertThat(notifier.userIds).isEmpty();
        verify(userSessionService, never()).currentUser("token-admin-1");
    }

    @Test
    void shouldFanOutThroughValidConnectionWhenSameUserHasStaleConnection() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        UserSessionService userSessionService = mock(UserSessionService.class);
        RecordingBusinessRealtimeNotifier notifier = new RecordingBusinessRealtimeNotifier();
        OnlineUserBusinessRealtimeFanOutPublisher publisher =
                new OnlineUserBusinessRealtimeFanOutPublisher(registry, userSessionService, notifier);
        CurrentUser admin = CurrentUser.systemUser("admin-1", "Admin");
        registry.register("ws-old", new CurrentUserPrincipal(admin, "token-admin-old"));
        registry.register("ws-new", new CurrentUserPrincipal(admin, "token-admin-new"));
        when(userSessionService.currentUserSnapshot("token-admin-old")).thenReturn(Optional.empty());
        when(userSessionService.currentUserSnapshot("token-admin-new")).thenReturn(Optional.of(admin));
        BusinessRealtimeEvent event = BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN");

        publisher.publish(event, currentUser -> true);

        assertThat(notifier.userIds).containsExactly("admin-1");
        assertThat(notifier.events).containsExactly(event);
    }

    @Test
    void shouldBuildRecordActionRecipientPolicies() {
        PlatformRecordActionAvailabilityService actionAvailabilityService =
                mock(PlatformRecordActionAvailabilityService.class);
        BusinessRealtimeRecipientPolicyFactory policyFactory =
                new BusinessRealtimeRecipientPolicyFactory(actionAvailabilityService);
        CurrentUser admin = CurrentUser.systemUser("admin-1", "Admin");
        CurrentUser viewer = CurrentUser.tenantUser("viewer-1", "Viewer", "demo");
        when(actionAvailabilityService.recordActions("iam.user", "user-1")).thenAnswer(invocation -> {
            String currentUserId = CurrentUserContext.currentUser()
                    .map(CurrentUser::userId)
                    .orElse(null);
            if ("admin-1".equals(currentUserId)) {
                return new PlatformRecordActionAvailability("user-1",
                        List.of(new PlatformRecordActionAvailability.Action("sessions", true, null)));
            }
            return new PlatformRecordActionAvailability("user-1",
                    List.of(new PlatformRecordActionAvailability.Action("sessions", false, "no data auth")));
        });

        BusinessRealtimeFanOutPublisher.RecipientPolicy policy =
                policyFactory.recordAction("iam.user", "user-1", "sessions");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(admin)) {
            assertThat(policy.canReceive(admin)).isTrue();
        }
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(viewer)) {
            assertThat(policy.canReceive(viewer)).isFalse();
        }
    }

    @Test
    void shouldPublishUserSessionCollectionChangesWithRecordActionPolicy() {
        BusinessRealtimeRecipientPolicyFactory policyFactory =
                mock(BusinessRealtimeRecipientPolicyFactory.class);
        BusinessRealtimeFanOutPublisher.RecipientPolicy policy = currentUser -> true;
        RecordingBusinessRealtimeFanOutPublisher fanOutPublisher = new RecordingBusinessRealtimeFanOutPublisher();
        UserSessionManagementRealtimeEventPublisher publisher =
                new UserSessionManagementRealtimeEventPublisher(fanOutPublisher, policyFactory);
        when(policyFactory.recordAction("iam.user", "user-1", "sessions")).thenReturn(policy);

        publisher.publish(UserSessionLifecycleEvent.loggedIn("user-1", "session-1"));

        assertThat(fanOutPublisher.event).isEqualTo(
                BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_IN"));
        assertThat(fanOutPublisher.recipientPolicy).isSameAs(policy);
    }

    @Test
    void shouldPublishUserSessionCollectionChangesWhenUserLogsOut() {
        BusinessRealtimeRecipientPolicyFactory policyFactory =
                mock(BusinessRealtimeRecipientPolicyFactory.class);
        BusinessRealtimeFanOutPublisher.RecipientPolicy policy = currentUser -> true;
        RecordingBusinessRealtimeFanOutPublisher fanOutPublisher = new RecordingBusinessRealtimeFanOutPublisher();
        UserSessionManagementRealtimeEventPublisher publisher =
                new UserSessionManagementRealtimeEventPublisher(fanOutPublisher, policyFactory);
        when(policyFactory.recordAction("iam.user", "user-1", "sessions")).thenReturn(policy);

        publisher.publish(UserSessionLifecycleEvent.loggedOut("user-1", "session-1"));

        assertThat(fanOutPublisher.event).isEqualTo(
                BusinessRealtimeEvent.userSessionCollectionChanged("user-1", "LOGGED_OUT"));
        assertThat(fanOutPublisher.recipientPolicy).isSameAs(policy);
    }

    @Test
    void shouldSendForceLogoutSecurityNotificationsToUserQueue() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompSecurityRealtimeNotifier notifier = new StompSecurityRealtimeNotifier(messagePublisher);

        notifier.notifyForceLogout("user-1");

        assertThat(messagePublisher.userId).isEqualTo("user-1");
        assertThat(messagePublisher.queue).isEqualTo(RealtimeDestinations.USER_NOTIFICATIONS);
        assertThat(messagePublisher.payload).isInstanceOf(RealtimeEnvelope.class);
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) messagePublisher.payload;
        assertThat(envelope.type()).isEqualTo(StompSecurityRealtimeNotifier.MESSAGE_TYPE);
        assertThat(envelope.payload()).isEqualTo(SecurityNotification.forceLogout());
    }

    @Test
    void shouldSendSessionRevokedSecurityNotificationsToUserQueue() {
        RecordingRealtimeMessagePublisher messagePublisher = new RecordingRealtimeMessagePublisher();
        StompSecurityRealtimeNotifier notifier = new StompSecurityRealtimeNotifier(messagePublisher);

        notifier.notifySessionRevoked("user-1", "session-1");

        assertThat(messagePublisher.userId).isEqualTo("user-1");
        assertThat(messagePublisher.queue).isEqualTo(RealtimeDestinations.USER_NOTIFICATIONS);
        assertThat(messagePublisher.payload).isInstanceOf(RealtimeEnvelope.class);
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) messagePublisher.payload;
        assertThat(envelope.type()).isEqualTo(StompSecurityRealtimeNotifier.MESSAGE_TYPE);
        assertThat(envelope.payload()).isEqualTo(SecurityNotification.sessionRevoked("session-1"));
    }

    @Test
    void shouldAdaptUserSecurityEventsToSessionRevocationAndRealtimeNotifications() {
        RecordingSecurityRealtimeNotifier notifier = new RecordingSecurityRealtimeNotifier();
        UserSecurityRealtimeEventPublisher publisher = new UserSecurityRealtimeEventPublisher(notifier);

        publisher.publish(UserSecurityEvent.passwordChanged("user-1"));
        publisher.publish(UserSecurityEvent.passwordReset("user-2"));
        publisher.publish(UserSecurityEvent.forceLogout("user-3"));

        assertThat(notifier.changedUserIds).containsExactly("user-1");
        assertThat(notifier.resetUserIds).containsExactly("user-2");
        assertThat(notifier.forceLogoutUserIds).containsExactly("user-3");
    }

    @Test
    void shouldAdaptSessionRevokedEventToTargetedRealtimeNotification() {
        RecordingSecurityRealtimeNotifier notifier = new RecordingSecurityRealtimeNotifier();
        UserSecurityRealtimeEventPublisher publisher = new UserSecurityRealtimeEventPublisher(notifier);

        publisher.publish(UserSecurityEvent.sessionRevoked("user-1", "session-1"));

        assertThat(notifier.revokedSessions).containsExactly("user-1:session-1");
    }

    @Test
    void shouldRejectRealtimeSubscribeWhenBoundSessionIsRevoked() {
        UserSessionService userSessionService = mock(UserSessionService.class);
        RealtimeConnectionRegistry connectionRegistry = new RealtimeConnectionRegistry();
        RealtimeAuthenticationChannelInterceptor interceptor =
                new RealtimeAuthenticationChannelInterceptor(userSessionService, connectionRegistry);
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        when(userSessionService.currentUser("token-1"))
                .thenReturn(Optional.of(currentUser), Optional.empty());
        when(userSessionService.currentSessionId("token-1")).thenReturn(Optional.of("login-session-1"));

        Message<?> connected = interceptor.preSend(stompMessage(StompCommand.CONNECT, null, "token-1", "ws-1"),
                mock(MessageChannel.class));
        CurrentUserPrincipal principal = (CurrentUserPrincipal) StompHeaderAccessor.wrap(connected).getUser();

        assertThat(principal.currentUser()).isEqualTo(currentUser);
        assertThat(principal.token()).isEqualTo("token-1");
        assertThat(principal.loginSessionId()).isEqualTo("login-session-1");
        assertThat(connectionRegistry.principals()).contains(principal);
        assertThatThrownBy(() -> interceptor.preSend(stompMessage(StompCommand.SUBSCRIBE, principal, null,
                "ws-1"),
                mock(MessageChannel.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("realtime authentication required");
    }

    @Test
    void shouldUnregisterRealtimeConnectionOnDisconnect() {
        UserSessionService userSessionService = mock(UserSessionService.class);
        RealtimeConnectionRegistry connectionRegistry = new RealtimeConnectionRegistry();
        RealtimeAuthenticationChannelInterceptor interceptor =
                new RealtimeAuthenticationChannelInterceptor(userSessionService, connectionRegistry);
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        when(userSessionService.currentUser("token-1")).thenReturn(Optional.of(currentUser));
        when(userSessionService.currentSessionId("token-1")).thenReturn(Optional.of("login-session-1"));

        Message<?> connected = interceptor.preSend(stompMessage(StompCommand.CONNECT, null, "token-1", "ws-1"),
                mock(MessageChannel.class));
        CurrentUserPrincipal principal = (CurrentUserPrincipal) StompHeaderAccessor.wrap(connected).getUser();

        interceptor.preSend(stompMessage(StompCommand.DISCONNECT, principal, null, "ws-1"),
                mock(MessageChannel.class));

        assertThat(connectionRegistry.principals()).isEmpty();
    }

    @Test
    void shouldResolveSessionPresenceFromRealtimeConnections() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "User", "tenant-a");

        registry.register("ws-1", new CurrentUserPrincipal(currentUser, "token-1", "login-session-1"));
        registry.register("ws-2", new CurrentUserPrincipal(currentUser, "token-1", "login-session-1"));

        assertThat(registry.presenceOf("login-session-1").present()).isTrue();
        assertThat(registry.presenceOf("login-session-1").connectionCount()).isEqualTo(2);
        assertThat(registry.presenceOf("other-session").present()).isFalse();
    }

    @Test
    void shouldKeepOriginalConnectedAtWhenRefreshingRealtimeConnection() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T08:00:00Z"));
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(clock);
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        CurrentUserPrincipal principal = new CurrentUserPrincipal(currentUser, "token-1", "login-session-1");

        registry.register("ws-1", principal);
        clock.instant = Instant.parse("2026-07-16T08:01:00Z");
        registry.register("ws-1", principal);

        assertThat(registry.presenceOf("login-session-1").lastConnectedAt())
                .isEqualTo(Instant.parse("2026-07-16T08:00:00Z"));
        assertThat(registry.presenceOf("login-session-1").lastObservedAt())
                .isEqualTo(Instant.parse("2026-07-16T08:01:00Z"));
    }

    @Test
    void shouldPublishPresenceIdleTransitionFromBackendScheduler() {
        Clock registryClock = Clock.fixed(Instant.parse("2026-07-16T07:56:00Z"), ZoneOffset.UTC);
        Clock notifierClock = Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(registryClock);
        UserSessionService userSessionService = mock(UserSessionService.class);
        RecordingApplicationEventPublisher publisher = new RecordingApplicationEventPublisher();
        UserSessionPresenceIdleNotifier notifier =
                new UserSessionPresenceIdleNotifier(registry, userSessionService, publisher, notifierClock);
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        registry.register("ws-1", new CurrentUserPrincipal(currentUser, "token-1", "login-session-1"));
        when(userSessionService.sessionIdleSince(eq("login-session-1"), any(), eq(notifierClock.instant())))
                .thenReturn(true);

        notifier.publishIdleTransitions();
        notifier.publishIdleTransitions();

        assertThat(publisher.events).containsExactly(
                UserSessionLifecycleEvent.presenceIdle("user-1", "login-session-1"));

        notifier.publishActiveIfIdle(new CurrentUserPrincipal(currentUser, "token-1", "login-session-1"));
        notifier.publishIdleTransitions();

        assertThat(publisher.events).containsExactly(
                UserSessionLifecycleEvent.presenceIdle("user-1", "login-session-1"),
                UserSessionLifecycleEvent.presenceActive("user-1", "login-session-1"),
                UserSessionLifecycleEvent.presenceIdle("user-1", "login-session-1"));
    }

    private static final class RecordingRealtimeMessagePublisher implements RealtimeMessagePublisher {
        private RealtimeTopic topic;
        private String userId;
        private RealtimeQueue queue;
        private Object payload;
        private final List<String> users = new ArrayList<>();
        private final List<UserMessage> userMessages = new ArrayList<>();
        private final List<BroadcastMessage> broadcasts = new ArrayList<>();

        @Override
        public void broadcast(RealtimeTopic topic, Object payload) {
            this.topic = topic;
            this.payload = payload;
            broadcasts.add(new BroadcastMessage(topic, payload));
        }

        @Override
        public void sendToUser(String userId, RealtimeQueue queue, Object payload) {
            this.userId = userId;
            this.queue = queue;
            this.payload = payload;
            users.add(userId);
            userMessages.add(new UserMessage(userId, queue, payload));
        }
    }

    private record UserMessage(String userId, RealtimeQueue queue, Object payload) {
    }

    private record BroadcastMessage(RealtimeTopic topic, Object payload) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private Message<?> stompMessage(StompCommand command, CurrentUserPrincipal principal, String token) {
        return stompMessage(command, principal, token, null);
    }

    private Message<?> stompMessage(StompCommand command, CurrentUserPrincipal principal, String token,
                                    String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        if (token != null) {
            accessor.addNativeHeader("Authorization", "Bearer " + token);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static final class RecordingBusinessRealtimeNotifier implements BusinessRealtimeNotifier {
        private final List<String> userIds = new ArrayList<>();
        private final List<BusinessRealtimeEvent> events = new ArrayList<>();

        @Override
        public void notifyUser(String userId, BusinessRealtimeEvent event) {
            userIds.add(userId);
            events.add(event);
        }
    }

    private static final class RecordingApplicationEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    private static final class RecordingBusinessRealtimeFanOutPublisher implements BusinessRealtimeFanOutPublisher {
        private BusinessRealtimeEvent event;
        private RecipientPolicy recipientPolicy;

        @Override
        public void publish(BusinessRealtimeEvent event, RecipientPolicy recipientPolicy) {
            this.event = event;
            this.recipientPolicy = recipientPolicy;
        }
    }

    private static final class RecordingSecurityRealtimeNotifier implements SecurityRealtimeNotifier {
        private final List<String> changedUserIds = new ArrayList<>();
        private final List<String> resetUserIds = new ArrayList<>();
        private final List<String> forceLogoutUserIds = new ArrayList<>();
        private final List<String> revokedSessions = new ArrayList<>();

        @Override
        public void notifyPasswordChanged(String userId) {
            changedUserIds.add(userId);
        }

        @Override
        public void notifyPasswordReset(String userId) {
            resetUserIds.add(userId);
        }

        @Override
        public void notifyForceLogout(String userId) {
            forceLogoutUserIds.add(userId);
        }

        @Override
        public void notifySessionRevoked(String userId, String sessionId) {
            revokedSessions.add(userId + ":" + sessionId);
        }
    }
}
