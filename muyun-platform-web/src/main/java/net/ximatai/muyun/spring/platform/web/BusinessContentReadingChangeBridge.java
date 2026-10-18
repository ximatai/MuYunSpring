package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentReadingChanged;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP receipts and background completions deliver the same committed, tenant-scoped read invalidation. */
@Component
public final class BusinessContentReadingChangeBridge {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BusinessContentReadingChangeBridge.class);
    private final DataChangeRealtimePublisher publisher;
    public BusinessContentReadingChangeBridge(DataChangeRealtimePublisher publisher) {
        this.publisher = java.util.Objects.requireNonNull(publisher);
    }
    @EventListener
    public void changed(BusinessContentReadingChanged event) {
        var change = new DataChange(BusinessContentReadingChanged.TYPE, event.moduleAlias(), null, null, null, Map.of());
        var mutation = MutationContextHolder.current().orElse(null);
        if (mutation != null) { mutation.record(change); return; }
        var actor = CurrentUserContext.currentUser().orElse(null);
        if (actor == null) return;
        var committed = new CommittedChangeSet(UUID.randomUUID().toString(), List.of(change));
        TransactionScopeSupport.afterCommitOrNow(() -> {
            try (var identity = CurrentUserContext.use(actor);
                 var tenant = event.tenantId() == null ? TenantContext.system("business content reading notification") : TenantContext.use(event.tenantId())) {
                publisher.publish(committed);
            } catch (RuntimeException failure) {
                LOG.warn("Failed to notify committed business content reading change for {}", event.moduleAlias(), failure);
            }
        });
    }
}
