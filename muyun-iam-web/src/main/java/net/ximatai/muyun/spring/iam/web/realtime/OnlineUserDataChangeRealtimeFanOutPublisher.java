package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.action.DataChangeTypes;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.web.realtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.function.Predicate;

/** Other users receive authorized collection invalidations, never record identifiers or business facts. */
public final class OnlineUserDataChangeRealtimeFanOutPublisher implements DataChangeRealtimePublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OnlineUserDataChangeRealtimeFanOutPublisher.class);
    private final RealtimeConnectionRegistry connections;
    private final UserSessionService sessions;
    private final RealtimeMessagePublisher messages;
    private final Predicate<String> canQueryModule;

    public OnlineUserDataChangeRealtimeFanOutPublisher(RealtimeConnectionRegistry connections,
            UserSessionService sessions, RealtimeMessagePublisher messages, Predicate<String> canQueryModule) {
        this.connections = Objects.requireNonNull(connections);
        this.sessions = Objects.requireNonNull(sessions);
        this.messages = Objects.requireNonNull(messages);
        this.canQueryModule = Objects.requireNonNull(canQueryModule);
    }

    private static boolean isPageConfigurationChange(DataChange change) {
        return DataChangeTypes.MODULE_PAGE_CONFIGURATION_CHANGED.equals(change.type())
                && change.recordId() == null && change.resourceKey() == null && change.scope() == null;
    }

    @Override
    public void publish(CommittedChangeSet changeSet) {
        if (changeSet == null || changeSet.changes().isEmpty()) return;
        var source = CurrentUserContext.currentUser().orElse(null);
        if (source == null) return;
        // Use the operation's active tenant, including a system user's selected business tenant.
        String tenantId = TenantContext.currentTenantId().orElse(null);
        String traceId = RequestTraceContext.currentTraceId().orElse(null);
        var delivered = new HashSet<String>();
        for (var principal : connections.principals()) {
            try {
                CurrentUser recipient;
                try (var lookup = TenantContext.bypassTenantFilter("data change recipient session lookup")) {
                    recipient = sessions.currentUserSnapshot(principal.token()).orElse(null);
                }
                if (recipient == null || recipient.passwordChangeRequired()
                        || source.userId().equals(recipient.userId())
                        || !delivered.add(recipient.userId())) continue;
                boolean samePartition = recipient.system() || Objects.equals(tenantId, recipient.tenantId());
                String recipientTenant = recipient.system() ? tenantId : recipient.tenantId();
                try (var userScope = CurrentUserContext.use(recipient);
                     var tenantScope = recipientTenant == null ? TenantContext.system("data change recipient") : TenantContext.use(recipientTenant)) {
                    var hints = changeSet.changes().stream().filter(Objects::nonNull)
                            .filter(change -> samePartition || isPageConfigurationChange(change))
                            .map(change -> isPageConfigurationChange(change)
                                    ? new DataChange(DataChangeTypes.MODULE_PAGE_CONFIGURATION_CHANGED,
                                            change.moduleAlias(), null, null, null, java.util.Map.of())
                                    : DataChange.collectionChanged(change.moduleAlias()))
                            .distinct().filter(change -> canQueryModule.test(change.moduleAlias())).toList();
                    if (!hints.isEmpty()) messages.sendToUser(recipient.userId(), RealtimeDestinations.DATA_CHANGES,
                            RealtimeEnvelope.of(StompDataChangeRealtimePublisher.MESSAGE_TYPE, traceId,
                                    new CommittedChangeSet(changeSet.changeSetId(), hints)));
                }
            } catch (RuntimeException failure) {
                // An unavailable recipient or policy must not break the committed business operation.
                LOGGER.debug("Skip data change recipient", failure);
            }
        }
    }
}
