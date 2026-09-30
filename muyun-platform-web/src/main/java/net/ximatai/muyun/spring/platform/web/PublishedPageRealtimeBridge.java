package net.ximatai.muyun.spring.platform.web;

import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventListener;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.springframework.stereotype.Component;

/** Projects a committed configuration fact to the existing authorized module subscriptions. */
@Component
public class PublishedPageRealtimeBridge implements RuntimeEventListener {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublishedPageRealtimeBridge.class);
    public static final String CHANGE_TYPE = "module-page-configuration-changed";
    private final DataChangeRealtimePublisher publisher;

    public PublishedPageRealtimeBridge(DataChangeRealtimePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onRuntimeEvent(RuntimeEvent event) {
        if (event.eventType() != RuntimeEventType.MODULE_PAGE_CONFIG_PUBLISHED) return;
        try {
            publisher.publish(new CommittedChangeSet(event.eventId(), List.of(new DataChange(
                    CHANGE_TYPE, event.moduleAlias(), null, null, null, Map.of()))));
        } catch (RuntimeException cause) {
            // The publication has committed; an unavailable notification channel cannot undo it.
            log.warn("Failed to notify published page configuration for {}", event.moduleAlias(), cause);
        }
    }
}
