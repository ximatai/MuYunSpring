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
    private ConfigurationTranslationImpactResolver impacts;
    public ConfigurationTranslationRealtimeBridge(DataChangeRealtimePublisher publisher) { this.publisher = publisher; }
    @org.springframework.beans.factory.annotation.Autowired
    public ConfigurationTranslationRealtimeBridge(DataChangeRealtimePublisher publisher, ConfigurationTranslationImpactResolver impacts) {
        this(publisher); this.impacts = impacts;
    }
    @Override public void onRuntimeEvent(RuntimeEvent event) {
        if (event.eventType() != RuntimeEventType.CONFIG_TRANSLATION_PUBLISHED) return;
        try {
            var modules = impacts == null ? List.of(event.moduleAlias()) : impacts.modules(event);
            publisher.publish(new CommittedChangeSet(event.eventId(), modules.stream().map(module -> new DataChange(
                    DataChangeTypes.CONFIGURATION_TRANSLATIONS_CHANGED, module, null, null, null, java.util.Map.of())).toList()));
        } catch (RuntimeException cause) { LOG.warn("Failed to notify translation publication for {}", event.moduleAlias(), cause); }
    }
}
