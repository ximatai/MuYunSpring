package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailabilityService;
import net.ximatai.muyun.spring.platform.web.realtime.BusinessRealtimeRecipientPolicyFactory;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipientResolver;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationService;
import net.ximatai.muyun.spring.platform.notification.TransactionalBusinessNotificationService;
import net.ximatai.muyun.spring.platform.web.notification.BusinessNotificationNotifier;
import net.ximatai.muyun.spring.platform.web.notification.StompBusinessNotificationNotifier;
import net.ximatai.muyun.spring.web.realtime.*;
import net.ximatai.muyun.spring.web.MuYunSpringCorsProperties;
import net.ximatai.muyun.spring.iam.user.UserSecurityEventPublisher;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class MuYunSpringRealtimeConfiguration implements WebSocketMessageBrokerConfigurer {
    private final UserSessionService userSessionService;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final MuYunSpringCorsProperties corsProperties;
    private final ApplicationEventPublisher applicationEventPublisher;

    public MuYunSpringRealtimeConfiguration(UserSessionService userSessionService,
                                            RealtimeConnectionRegistry connectionRegistry,
                                            ObjectProvider<MuYunSpringCorsProperties> corsProperties,
                                            ApplicationEventPublisher applicationEventPublisher) {
        this.userSessionService = userSessionService;
        this.connectionRegistry = connectionRegistry;
        this.corsProperties = corsProperties.getIfAvailable();
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws/platform");
        List<String> allowedOrigins = corsProperties == null ? List.of() : corsProperties.getAllowedOrigins();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            endpoint.setAllowedOrigins(allowedOrigins.toArray(String[]::new));
        } else {
            endpoint.setAllowedOriginPatterns("*");
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new RealtimeAuthenticationChannelInterceptor(userSessionService,
                connectionRegistry, applicationEventPublisher));
    }

    @Bean
    @ConditionalOnMissingBean(RealtimeConnectionRegistry.class)
    public static RealtimeConnectionRegistry realtimeConnectionRegistry() {
        return new RealtimeConnectionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(UserSessionPresenceIdleNotifier.class)
    public UserSessionPresenceIdleNotifier userSessionPresenceIdleNotifier(
            RealtimeConnectionRegistry connectionRegistry,
            UserSessionService userSessionService,
            ApplicationEventPublisher applicationEventPublisher) {
        return new UserSessionPresenceIdleNotifier(connectionRegistry, userSessionService, applicationEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean(RealtimeMessagePublisher.class)
    public RealtimeMessagePublisher realtimeMessagePublisher(SimpMessagingTemplate messagingTemplate) {
        return new StompRealtimeMessagePublisher(messagingTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(DataChangeRealtimePublisher.class)
    public DataChangeRealtimePublisher dataChangeRealtimePublisher(RealtimeMessagePublisher messagePublisher) {
        return new StompDataChangeRealtimePublisher(messagePublisher);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityRealtimeNotifier.class)
    public SecurityRealtimeNotifier securityRealtimeNotifier(RealtimeMessagePublisher messagePublisher) {
        return new StompSecurityRealtimeNotifier(messagePublisher);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessRealtimeNotifier.class)
    public BusinessRealtimeNotifier businessRealtimeNotifier(RealtimeMessagePublisher messagePublisher) {
        return new StompBusinessRealtimeNotifier(messagePublisher);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessNotificationNotifier.class)
    public BusinessNotificationNotifier businessNotificationNotifier(RealtimeMessagePublisher messagePublisher) {
        return new StompBusinessNotificationNotifier(messagePublisher);
    }

    @Bean
    @ConditionalOnBean(BusinessNotificationRecipientResolver.class)
    @ConditionalOnMissingBean(BusinessNotificationDelivery.class)
    public BusinessNotificationDelivery businessNotificationDelivery(
            BusinessNotificationRecipientResolver recipientResolver,
            BusinessNotificationNotifier notifier) {
        return new OnlineBusinessNotificationDelivery(
                connectionRegistry, userSessionService, recipientResolver, notifier);
    }

    @Bean
    @ConditionalOnBean(BusinessNotificationRecipientResolver.class)
    @ConditionalOnMissingBean(BusinessNotificationService.class)
    public BusinessNotificationService businessNotificationService(BusinessNotificationDelivery delivery) {
        return new TransactionalBusinessNotificationService(delivery);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessRealtimeFanOutPublisher.class)
    public BusinessRealtimeFanOutPublisher businessRealtimeFanOutPublisher(
            BusinessRealtimeNotifier businessRealtimeNotifier) {
        return new OnlineUserBusinessRealtimeFanOutPublisher(
                connectionRegistry, userSessionService, businessRealtimeNotifier);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessRealtimeRecipientPolicyFactory.class)
    public BusinessRealtimeRecipientPolicyFactory businessRealtimeRecipientPolicyFactory(
            ObjectProvider<PlatformRecordActionAvailabilityService> actionAvailabilityService) {
        return new BusinessRealtimeRecipientPolicyFactory(actionAvailabilityService::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean(UserSecurityEventPublisher.class)
    public UserSecurityEventPublisher userSecurityEventPublisher(SecurityRealtimeNotifier securityRealtimeNotifier) {
        return new UserSecurityRealtimeEventPublisher(securityRealtimeNotifier);
    }

    @Bean
    @ConditionalOnMissingBean(UserSessionManagementRealtimeEventPublisher.class)
    public UserSessionManagementRealtimeEventPublisher userSessionManagementRealtimeEventPublisher(
            BusinessRealtimeFanOutPublisher businessRealtimeFanOutPublisher,
            BusinessRealtimeRecipientPolicyFactory recipientPolicyFactory) {
        return new UserSessionManagementRealtimeEventPublisher(
                businessRealtimeFanOutPublisher, recipientPolicyFactory);
    }
}
