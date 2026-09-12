package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import net.ximatai.muyun.spring.platform.ui.PlatformPageEntryContext;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BusinessLogPageAccessRecorderTest {
    private static final class RecordingPublisher implements BusinessLogPublisher {
        private final java.util.List<BusinessLogEvent> events = new java.util.ArrayList<>();

        @Override public BusinessLogWriteResult publish(BusinessLogEvent event) {
            events.add(event);
            return new BusinessLogWriteResult(event.eventId(), BusinessLogWriteResult.Status.APPENDED);
        }

        @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) {
            return events.stream().map(this::publish).toList();
        }
    }

    @Test
    void shouldSnapshotCurrentOperatorOrganizationOnPageAccess() {
        RecordingPublisher publisher = new RecordingPublisher();
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "sales.contract", MenuPageMode.LIST, null, null, null),
                PlatformUiClientType.WEB, PlatformResolvedPageConfig.empty());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "Alice", "tenant-a", "organization-a"))) {
            new BusinessLogPageAccessRecorder(publisher).recordSuccessfulMenuEntry(bootstrap);
        }

        assertThat(publisher.events).singleElement().satisfies(event -> {
            assertThat(event.context().operatorId()).isEqualTo("user-1");
            assertThat(event.context().operatorOrganizationId()).isEqualTo("organization-a");
        });
    }

    @Test
    void shouldNotBlockSuccessfulPageDeliveryWhenPublicationFails() {
        BusinessLogPublisher failingPublisher = new BusinessLogPublisher() {
            @Override public BusinessLogWriteResult publish(BusinessLogEvent event) { throw new IllegalStateException("down"); }
            @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) { throw new IllegalStateException("down"); }
        };
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "sales.contract", MenuPageMode.LIST, null, null, null),
                PlatformUiClientType.WEB, PlatformResolvedPageConfig.empty());

        assertThatCode(() -> new BusinessLogPageAccessRecorder(failingPublisher).recordSuccessfulMenuEntry(bootstrap))
                .doesNotThrowAnyException();
    }
}
