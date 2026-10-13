package net.ximatai.muyun.spring.platform.web;

import java.util.List;
import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.springframework.stereotype.Component;

/** A committed text publication refreshes presentation subscriptions, never executable page plans. */
@Component
public class ConfigurationTranslationRealtimeBridge implements RuntimeEventListener {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConfigurationTranslationRealtimeBridge.class);
    private final DataChangeRealtimePublisher publisher;
    public ConfigurationTranslationRealtimeBridge(DataChangeRealtimePublisher publisher) { this.publisher = publisher; }
    @Override public void onRuntimeEvent(RuntimeEvent event) {
        if (event.eventType() != RuntimeEventType.CONFIG_TRANSLATION_PUBLISHED) return;
        try {
            publisher.publish(new CommittedChangeSet(event.eventId(), List.of(new DataChange(
                    DataChangeTypes.CONFIGURATION_TRANSLATIONS_CHANGED, event.moduleAlias(), null, null, null, event.payload()))));
        } catch (RuntimeException cause) { LOG.warn("Failed to notify translation publication for {}", event.moduleAlias(), cause); }
    }
}
