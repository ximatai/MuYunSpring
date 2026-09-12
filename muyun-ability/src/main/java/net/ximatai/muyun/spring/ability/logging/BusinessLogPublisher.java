package net.ximatai.muyun.spring.ability.logging;

import java.util.Collection;
import java.util.List;

/** Collector-facing boundary that publishes typed business facts without exposing a storage adapter. */
public interface BusinessLogPublisher {
    BusinessLogWriteResult publish(BusinessLogEvent event);

    List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events);
}
