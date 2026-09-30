package net.ximatai.muyun.spring.platform.web;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PublishedPageRealtimeBridgeTest {
    @Test
    void projectsOnlyCommittedConfigurationFactsWithoutConfigurationPayload() {
        var delivered = new AtomicReference<CommittedChangeSet>();
        var bridge = new PublishedPageRealtimeBridge(delivered::set);
        bridge.onRuntimeEvent(event(RuntimeEventType.MODULE_REFRESHED));
        assertThat(delivered.get()).isNull();
        var event = event(RuntimeEventType.MODULE_PAGE_CONFIG_PUBLISHED);
        bridge.onRuntimeEvent(event);
        assertThat(delivered.get().changeSetId()).isEqualTo(event.eventId());
        assertThat(delivered.get().changes()).singleElement().satisfies(change -> {
            assertThat(change.moduleAlias()).isEqualTo("demo.order");
            assertThat(change.type()).isEqualTo(PublishedPageRealtimeBridge.CHANGE_TYPE);
            assertThat(change.facts()).isEmpty();
        });
    }
    private RuntimeEvent event(RuntimeEventType type) {
        return RuntimeEvent.of(type, "demo.order", null, null, null, null, true,
                "test", RuntimeMutationSource.SYSTEM, Map.of("private", "not-for-broadcast"));
    }
}
