package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentReadingChanged;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentReadingChangeBridgeTest {
    @Test void putsManualChangesInTheCommittedReceiptWithoutIndependentBroadcast() {
        var publisher = mock(DataChangeRealtimePublisher.class);
        var bridge = new BusinessContentReadingChangeBridge(publisher);
        var mutation = new MutationContext();
        try (var scope = MutationContextHolder.use(mutation)) {
            bridge.changed(new BusinessContentReadingChanged("quality.category", "tenant-a"));
        }
        assertThat(mutation.committedChangeSet(null).changes()).containsExactly(
                new DataChange(BusinessContentReadingChanged.TYPE, "quality.category", null, null, null, Map.of()));
        verifyNoInteractions(publisher);
    }
    @Test void backgroundChangesWaitForCommitAndRetainTheTrustedTenantIdentity() {
        var received = new ArrayList<CommittedChangeSet>();
        var actor = CurrentUser.tenantUser("reviewer", "Reviewer", "tenant-a");
        var bridge = new BusinessContentReadingChangeBridge(changes -> {
            assertThat(CurrentUserContext.currentUser()).contains(actor);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            received.add(changes);
        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (var identity = CurrentUserContext.use(actor); var tenant = TenantContext.use("tenant-a")) {
                bridge.changed(new BusinessContentReadingChanged("quality.category", "tenant-a"));
            }
            assertThat(received).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().changes().getFirst().facts()).isEmpty();
            assertThat(CurrentUserContext.currentUser()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        received.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (var identity = CurrentUserContext.use(actor)) {
                bridge.changed(new BusinessContentReadingChanged("quality.category", "tenant-a"));
            }
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
            assertThat(received).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
